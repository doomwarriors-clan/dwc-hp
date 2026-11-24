# AGENTS.md – doomwarriors-clan/dwc-hp

Guidance for coding agents (and people) working on this repository. The
README (English) is the user and operations documentation; keep it up to
date with every change.

## The project around this repo

The doomwarriors clan (https://www.doomwarriors.de) runs four Quake III Arena
servers (Duel, TDM, CTF, Instagib) with the OSP mod. Repos in the GitHub org
`doomwarriors-clan`:

| Repo | What | Default branch |
|---|---|---|
| `Quake3e` | engine: fork of ec-/Quake3e | `dwc` |
| `q3-servers` (private) | Docker Compose deployment of the servers | `main` |
| `dwc-hp` (this) | clan homepage: news, clan wars, members, Quake 3 stats and demos, server control | `master` |
| `etqw-server` | Docker Compose deployment of the ET:QW 1.5 server (same host) | `main` |
| `osp` (planned) | OSP mod sources, released as "OSP 1.03a-dwc" | – |

Maintainers: Micha (`n4cer`) and Hannes (`dwc-motter`), both org owners.

## Working rules

- Open topics are GitHub issues. Run `gh issue list -R doomwarriors-clan/dwc-hp`
  before starting and reference the issue in the PR.
- Every change goes through a pull request against `master` (protected: no
  direct push, no force push).
- **Merging to `master` deploys to production automatically**
  (`.github/workflows/ci-deploy.yml`: test, package, upload, activate). The PR
  run executes the same tests; only merge green PRs.
- Never commit secrets: `conf/local.conf`, `/etc/dwc/environment` values,
  `play.http.secret.key` for production, `dwc_deploy_key*` (local SSH key,
  git-ignored).
- English everywhere: commit messages, PR texts, README and other docs, and
  code comments. Existing German comments are translated when a file is
  touched anyway. The site's user-facing texts are bilingual: `conf/messages`
  (German) and `conf/messages.en` (English) change together.

## Stack and commands

Java 17, Scala 2.13, Play Framework 3, Twirl, Ebean, PostgreSQL, sbt
(versions in `build.sbt`, `project/`).

```sh
sbt test                                  # needs PostgreSQL: db dwc, user postgres, password "bla" (conf/application.conf)
sbt -Dconfig.file=conf/local.conf run     # http://localhost:9000, admin at /admin
```

Schema changes go into a new evolution in `conf/evolutions/default/`; never
edit an evolution that already ran in production. For Claude Code on the web,
`.claude/hooks/session-start.sh` installs sbt and PostgreSQL.

## Layout

| Path | What |
|---|---|
| `app/controllers/` | endpoints and logic; Quake 3: `Quake3*.java`, `AdminQuake3Controller`, `N4botDemoImporter`, `ClanwarDemoStats` |
| `app/models/` | Ebean models |
| `app/views/` | Twirl templates |
| `conf/routes`, `conf/application.conf` | routing, configuration (env vars `DWC_*`) |
| `deploy/` | nginx, systemd units, `quake3ctl` sidecar for server start/stop |
| `test/` | tests |

## Contract with q3-servers (do not break silently)

The site reads the game servers' files from the q3-servers checkout on the
same host and controls the services. These are defined over there; a change
on either side needs the matching change on the other:

- `DWC_QUAKE3_LOG_FOLDER` → q3-servers `logs/`: `<mode>/games.log` for the
  statistics, `<mode>/demos/` with `mv-<yymmdd>-<hhmmss>.dm_71` and
  `…-<nr>-<name>.dm_68` for demo downloads (`<mode>` = duel, tdm, ctf,
  instagib).
- The servers' clock (games.log `ServerTime`, demo file names) is UTC: the
  containers set no `TZ`. `Quake3GamesLog.SERVER_ZONE` assumes that.
- `DWC_QUAKE3_PK3_FOLDER` → q3-servers `server-data/`: `.pk3` files directly in
  `baseq3/` and `osp/` are served at `/quake3/pk3/...` (the servers'
  `sv_dlURL`); the id paks `pak0`–`pak8` never.
- `deploy/quake3@.service` starts `q3server-<mode>` in
  `/home/micha/docker/q3-osp-server` via the root-owned `quake3ctl` sidecar
  (`DWC_QUAKE3_TOKEN` shared with `QUAKE3CTL_TOKEN`).
- `DWC_QUAKE3_PUBLIC_HOST`, `DWC_QUAKE3_{DUEL,TDM,CTF,INSTAGIB}_PORT` for the
  status board. The q3-servers test instance (UDP 27970) is deliberately not
  part of the site.

## Contract with etqw-server (do not break silently)

The ET:QW server (`doomwarriors-clan/etqw-server`) runs on the same host as
its own compose project. It shares the Quake 3 plumbing:

- `deploy/etqw.service` runs `docker compose up -d etqw-server` / `stop` in
  `/home/micha/docker/etqw-server`; `quake3ctl` controls it as `etqw`
  (`Quake3ControlClient.Server.ETQW`).
- `DWC_ETQW_PORT` (UDP 27733) for the status board, queried with the idTech 4
  `getInfo` packet (`Quake3StatusScheduler.parseEtqwInfoResponse`). The entry
  `etqw` in `/quake3-status.json` is scraped by my-observability
  (`q3_server_*{server="etqw"}`), so keep the id stable.
- No statistics, demos or pk3 downloads for ET:QW.
