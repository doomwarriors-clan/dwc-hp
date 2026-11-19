#!/usr/bin/env python3
"""Minimal privileged sidecar that lets the dwc admin panel start/stop the
Quake 3 and ET:QW docker-compose stacks and put files uploaded over SFTP into place,
without granting the dwc web process itself any Docker, systemd or file
system privileges beyond its own folders.

Runs as root under quake3ctl.service, bound to 127.0.0.1 only. It exposes
a fixed set of actions (status/start/stop for a hardcoded allowlist of
systemd units; list/install/discard for the SFTP upload folder and a
hardcoded allowlist of target folders), authenticated with a bearer token
shared with the dwc app via /etc/dwc/quake3ctl.env and /etc/dwc/environment.
It never invokes a shell and never accepts a unit name, a path or a file
name from the caller as raw text.
"""

import errno
import hmac
import http.server
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import threading
import time

# name (as used in admin panel URLs) -> systemd unit it controls.
UNITS = {
    "duel": "quake3@duel.service",
    "tdm": "quake3@tdm.service",
    "ctf": "quake3@ctf.service",
    "instagib": "quake3@instagib.service",
    # ET:QW, separate compose project (doomwarriors-clan/etqw-server).
    "etqw": "etqw.service",
}

TOKEN = os.environ.get("QUAKE3CTL_TOKEN", "")
PORT = int(os.environ.get("QUAKE3CTL_PORT", "8765"))

# Where the SFTP account drops new files, and where they may go from there.
UPLOAD_DIR = os.environ.get("QUAKE3CTL_UPLOAD_DIR", "/srv/upload/eingang")
PK3_DIR = os.environ.get("QUAKE3CTL_PK3_DIR", "/home/micha/docker/q3-osp-server/server-data")
DOWNLOAD_DIR = os.environ.get("QUAKE3CTL_DOWNLOAD_DIR", "/var/lib/dwc/downloads")
DEMO_DIR = os.environ.get("QUAKE3CTL_DEMO_DIR", os.path.join(DOWNLOAD_DIR, "clanwar-demos"))

PK3_NAME = re.compile(r".*\.pk3", re.IGNORECASE)
# Quake 3 demos as recorded or packed. Mirrors CLANWAR_DEMO in HomeController.
DEMO_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.(dm_\d{2}|zip|7z|rar)", re.IGNORECASE)

# target name -> (folder, pattern the file name must match, or None for any).
TARGETS = {
    "baseq3": (os.path.join(PK3_DIR, "baseq3"), PK3_NAME),
    "osp": (os.path.join(PK3_DIR, "osp"), PK3_NAME),
    "downloads": (DOWNLOAD_DIR, None),
    "demos": (DEMO_DIR, DEMO_NAME),
}

# Plain names only: no path separators, no leading dot, nothing a shell or
# the URL could misread. The admin panel mirrors this (AdminUploadController).
UPLOAD_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
# The retail id paks in baseq3 never change; replacing one could only break
# the servers. (A mod folder like osp may ship its own pak0.pk3.)
ID_PAK = re.compile(r"pak[0-8]\.pk3", re.IGNORECASE)
# A file changed this recently is probably still being uploaded.
SETTLE_SECONDS = 60
FILE_MODE = 0o644

# Installs move files between folders; one at a time keeps that simple.
INSTALL_LOCK = threading.Lock()


class UploadError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def upload_entries():
    """The regular files in the upload folder, with the files they would replace."""
    entries = []
    try:
        names = sorted(os.listdir(UPLOAD_DIR), key=str.lower)
    except FileNotFoundError:
        names = []
    for name in names:
        try:
            info = os.lstat(os.path.join(UPLOAD_DIR, name))
        except FileNotFoundError:
            continue
        if not stat.S_ISREG(info.st_mode):
            continue
        replaces = {}
        if UPLOAD_NAME.fullmatch(name):
            for target, (folder, _) in TARGETS.items():
                try:
                    existing = os.lstat(os.path.join(folder, name))
                except OSError:
                    continue
                if stat.S_ISREG(existing.st_mode):
                    replaces[target] = {"size": existing.st_size, "mtime": int(existing.st_mtime)}
        entries.append({"name": name, "size": info.st_size, "mtime": int(info.st_mtime), "replaces": replaces})
    return entries


def open_upload(name):
    """Opens an uploaded file without following links, as the descriptor all checks run on."""
    if not UPLOAD_NAME.fullmatch(name):
        raise UploadError(400, "invalid file name")
    try:
        fd = os.open(os.path.join(UPLOAD_DIR, name), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        raise UploadError(404, "no such upload")
    except OSError:
        raise UploadError(400, "not a regular file")
    info = os.fstat(fd)
    # A hard link would let the upload account hand in a file it does not own.
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        os.close(fd)
        raise UploadError(400, "not a regular file")
    return fd, info


def install_upload(name, target):
    if target not in TARGETS:
        raise UploadError(404, "unknown target")
    folder, allowed = TARGETS[target]
    if allowed is not None and not allowed.fullmatch(name):
        raise UploadError(400, "this kind of file cannot go there")
    if target == "baseq3" and ID_PAK.fullmatch(name):
        raise UploadError(400, "the id paks cannot be replaced")
    try:
        folder_info = os.stat(folder)
    except FileNotFoundError:
        raise UploadError(500, "target folder missing")
    source = os.path.join(UPLOAD_DIR, name)
    partial = os.path.join(folder, ".upload-" + name + ".partial")
    final = os.path.join(folder, name)

    with INSTALL_LOCK:
        fd, info = open_upload(name)
        try:
            if time.time() - info.st_mtime < SETTLE_SECONDS:
                raise UploadError(409, "file changed within the last minute; upload still running?")
            if os.path.lexists(partial):
                os.unlink(partial)
            moved = False
            try:
                try:
                    # Same file system: the move is instant and needs no extra space.
                    os.rename(source, partial)
                    moved = True
                    renamed = os.lstat(partial)
                    if (renamed.st_dev, renamed.st_ino) != (info.st_dev, info.st_ino):
                        raise UploadError(409, "upload changed while installing")
                    target_fd = os.dup(fd)
                except OSError as error:
                    if error.errno != errno.EXDEV:
                        raise
                    target_fd = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                    with os.fdopen(os.dup(fd), "rb") as reader, os.fdopen(os.dup(target_fd), "wb") as writer:
                        shutil.copyfileobj(reader, writer, 1024 * 1024)
                try:
                    # Owned like the folder it lands in, readable by the game servers and the web app.
                    os.fchown(target_fd, folder_info.st_uid, folder_info.st_gid)
                    os.fchmod(target_fd, FILE_MODE)
                    os.fsync(target_fd)
                finally:
                    os.close(target_fd)
                # Atomic: a running server or a download in progress keeps the old file's bytes.
                os.replace(partial, final)
            except BaseException:
                # Leave nothing half-done in the target folder; a moved upload goes back.
                if moved and not os.path.lexists(source):
                    os.rename(partial, source)
                elif os.path.lexists(partial):
                    os.unlink(partial)
                raise
            if not moved:
                os.unlink(source)
        finally:
            os.close(fd)


def discard_upload(name):
    if not UPLOAD_NAME.fullmatch(name):
        raise UploadError(400, "invalid file name")
    path = os.path.join(UPLOAD_DIR, name)
    with INSTALL_LOCK:
        try:
            info = os.lstat(path)
        except FileNotFoundError:
            raise UploadError(404, "no such upload")
        if not stat.S_ISREG(info.st_mode):
            raise UploadError(400, "not a regular file")
        os.unlink(path)


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "quake3ctl/1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _unauthorized(self):
        reason = "no token configured" if not TOKEN else "token mismatch"
        self._respond_text(401, "unauthorized: " + reason + "\n")

    def _authorized(self):
        if not TOKEN:
            return False
        header = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not header.startswith(prefix):
            return False
        return hmac.compare_digest(header[len(prefix):], TOKEN)

    def _respond_text(self, status, body):
        self._respond(status, "text/plain; charset=utf-8", body)

    def _respond(self, status, content_type, body):
        encoded = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _unit_or_404(self, name):
        unit = UNITS.get(name)
        if unit is None:
            self._respond_text(404, "unknown server\n")
        return unit

    def _is_active_state(self, unit):
        result = subprocess.run(
            ["/usr/bin/systemctl", "is-active", unit],
            capture_output=True, text=True, timeout=10,
        )
        return result.stdout.strip() or "unknown"

    def _upload_action(self, action, *args):
        try:
            action(*args)
        except UploadError as error:
            return self._respond_text(error.status, str(error) + "\n")
        except OSError as error:
            return self._respond_text(500, (error.strerror or "file operation failed") + "\n")
        self._respond_text(200, "ok\n")

    def do_GET(self):
        if not self._authorized():
            return self._unauthorized()
        parts = self.path.strip("/").split("/")
        if len(parts) == 2 and parts[0] == "status":
            unit = self._unit_or_404(parts[1])
            if unit is None:
                return
            return self._respond_text(200, self._is_active_state(unit))
        if parts == ["uploads"]:
            return self._respond(200, "application/json", json.dumps(upload_entries()))
        self._respond_text(404, "not found\n")

    def do_POST(self):
        if not self._authorized():
            return self._unauthorized()
        parts = self.path.strip("/").split("/")
        if len(parts) == 2 and parts[0] in ("start", "stop"):
            unit = self._unit_or_404(parts[1])
            if unit is None:
                return
            try:
                subprocess.run(
                    ["/usr/bin/systemctl", parts[0], unit],
                    capture_output=True, text=True, timeout=120, check=True,
                )
            except subprocess.CalledProcessError as error:
                return self._respond_text(502, error.stderr or "systemctl failed\n")
            except subprocess.TimeoutExpired:
                return self._respond_text(504, "timed out\n")
            return self._respond_text(200, self._is_active_state(unit))
        if len(parts) == 4 and parts[0] == "uploads" and parts[2] == "install":
            return self._upload_action(install_upload, parts[1], parts[3])
        if len(parts) == 3 and parts[0] == "uploads" and parts[2] == "discard":
            return self._upload_action(discard_upload, parts[1])
        self._respond_text(404, "not found\n")


def main():
    if not TOKEN:
        sys.exit("QUAKE3CTL_TOKEN must be set (see quake3ctl.env.example)")
    # Threaded, so status requests stay answered while a large upload is copied.
    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
