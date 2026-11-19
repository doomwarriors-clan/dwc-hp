package controllers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Cancellable;
import play.api.Configuration;
import play.inject.ApplicationLifecycle;
import scala.concurrent.ExecutionContext;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Periodically queries the game servers' native UDP query protocols directly
 * (not through the quake3ctl admin sidecar) so the public status shown to
 * every visitor reflects whether a player could actually connect, including
 * player counts, and doesn't depend on the admin-only control path being up.
 * The Quake 3 servers answer "getinfo"; the ET:QW server (doomwarriors-clan/
 * etqw-server, same host) answers the idTech 4 "getInfo" packet.
 */
@Singleton
final class Quake3StatusScheduler {
    private static final byte[] GETINFO = concat(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
            "getinfo".getBytes(StandardCharsets.US_ASCII));
    // idTech 4 / ET:QW: 0xFFFF + "getInfo" + NUL + a 4-byte challenge (zero),
    // the packet qstat sends to ETQW servers.
    private static final byte[] ETQW_GETINFO = concat(new byte[]{(byte) 0xFF, (byte) 0xFF},
            "getInfo\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII));
    private static final byte[] ETQW_INFO_RESPONSE = concat(new byte[]{(byte) 0xFF, (byte) 0xFF},
            "infoResponse\0".getBytes(StandardCharsets.US_ASCII));
    /** Client id that ends the player list (MAX_ASYNC_CLIENTS). */
    private static final int ETQW_PLAYER_LIST_END = 32;
    private static final int TIMEOUT_MILLIS = 750;

    enum Protocol { QUAKE3, ETQW }

    private record ServerConfig(String id, String label, String host, int port, Protocol protocol) { }

    /** What a server's info response says; players and maxPlayers may be null if not readable. */
    record Info(Integer players, Integer maxPlayers, String map) { }

    @Inject
    Quake3StatusScheduler(Configuration configuration, ActorSystem actorSystem, ApplicationLifecycle lifecycle) {
        String host = configured(configuration, "quake3.public.host");
        List<ServerConfig> servers = host.isBlank() ? List.of() : List.of(
                new ServerConfig("duel", "Duel", host, intConfigured(configuration, "quake3.public.duel.port"), Protocol.QUAKE3),
                new ServerConfig("tdm", "TDM", host, intConfigured(configuration, "quake3.public.tdm.port"), Protocol.QUAKE3),
                new ServerConfig("ctf", "CTF", host, intConfigured(configuration, "quake3.public.ctf.port"), Protocol.QUAKE3),
                new ServerConfig("instagib", "Instagib", host, intConfigured(configuration, "quake3.public.instagib.port"), Protocol.QUAKE3),
                new ServerConfig("etqw", "ET:QW", host, intConfigured(configuration, "quake3.public.etqw.port"), Protocol.ETQW))
                .stream().filter(server -> server.port() > 0).toList();
        if (servers.isEmpty()) return;

        ExecutorService executor = Executors.newFixedThreadPool(servers.size(), runnable -> {
            Thread thread = new Thread(runnable, "quake3-status");
            thread.setDaemon(true);
            return thread;
        });
        Runnable refresh = () -> Quake3StatusBoard.set(servers.stream().map(this::query).toList());
        Cancellable schedule = actorSystem.scheduler().scheduleWithFixedDelay(Duration.ofSeconds(2),
                Duration.ofSeconds(20), refresh, ExecutionContext.fromExecutor(executor));

        lifecycle.addStopHook(() -> {
            schedule.cancel();
            executor.shutdownNow();
            return CompletableFuture.completedFuture(null);
        });
    }

    private Quake3StatusBoard.ServerStatus query(ServerConfig config) {
        String address = config.host() + ":" + config.port();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            byte[] request = config.protocol() == Protocol.ETQW ? ETQW_GETINFO : GETINFO;
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName(config.host()), config.port()));
            byte[] buffer = new byte[8192];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            Info info;
            if (config.protocol() == Protocol.ETQW) {
                info = parseEtqwInfoResponse(buffer, response.getLength());
                if (info == null) throw new IOException("not an ET:QW info response");
            } else {
                Map<String, String> values = parseInfoResponse(buffer, response.getLength());
                info = new Info(parseInt(values.get("clients")), parseInt(values.get("sv_maxclients")), values.get("mapname"));
            }
            return new Quake3StatusBoard.ServerStatus(config.id(), config.label(), address, true,
                    info.players(), info.maxPlayers(), info.map());
        } catch (IOException e) {
            return new Quake3StatusBoard.ServerStatus(config.id(), config.label(), address, false, null, null, null);
        }
    }

    private static Map<String, String> parseInfoResponse(byte[] buffer, int length) {
        // Response is 0xFFFFFFFF + "infoResponse\n" + "\key\value\key\value...".
        String body = new String(buffer, 4, Math.max(0, length - 4), StandardCharsets.ISO_8859_1);
        int newline = body.indexOf('\n');
        String keyValues = newline >= 0 ? body.substring(newline + 1) : body;
        String[] parts = keyValues.split("\\\\", -1);
        Map<String, String> info = new LinkedHashMap<>();
        for (int i = 1; i + 1 < parts.length; i += 2) info.put(parts[i], parts[i + 1]);
        return info;
    }

    /**
     * Parses an ET:QW "infoResponse", following qstat's ETQW parser (doom3.c,
     * version 5): header, task id, challenge, protocol (major << 16 | minor),
     * size, then key NUL value NUL pairs up to an empty key, then the players
     * (id, ping, [rate], name, clan tag position, clan tag, bot flag) up to id 32.
     * Returns null if the packet is not an info response at all; players is
     * null if the player list cannot be read. Bots count as players, like in
     * the Quake 3 "clients" value.
     */
    static Info parseEtqwInfoResponse(byte[] buffer, int length) {
        if (length < ETQW_INFO_RESPONSE.length + 16) return null;
        for (int i = 0; i < ETQW_INFO_RESPONSE.length; i++) {
            if (buffer[i] != ETQW_INFO_RESPONSE[i]) return null;
        }
        ByteBuffer packet = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        packet.position(ETQW_INFO_RESPONSE.length);
        packet.getInt(); // task id
        packet.getInt(); // challenge
        int protocol = packet.getInt();
        packet.getInt(); // size

        Map<String, String> values = new LinkedHashMap<>();
        while (true) {
            String key = readString(packet);
            String value = readString(packet);
            if (key == null || value == null) return null;
            if (key.isEmpty()) break;
            values.put(key.toLowerCase(Locale.ROOT), value);
        }

        String map = values.get("si_map");
        if (map != null) {
            if (map.startsWith("maps/")) map = map.substring("maps/".length());
            if (map.endsWith(".entities")) map = map.substring(0, map.length() - ".entities".length());
        }
        return new Info(countEtqwPlayers(packet, protocol), parseInt(values.get("si_maxplayers")), map);
    }

    private static Integer countEtqwPlayers(ByteBuffer packet, int protocol) {
        // Protocols 13.9 and 13.10 pack the bot flag into a single bit (qstat
        // shifts every following byte). Not worth guessing: no count there.
        if (protocol == 0xd0009 || protocol == 0xd000a) return null;
        int players = 0;
        try {
            while (true) {
                int id = packet.get() & 0xFF;
                if (id == ETQW_PLAYER_LIST_END) return players;
                if (id > ETQW_PLAYER_LIST_END) return null;
                packet.getShort(); // ping
                if (protocol <= 0xa0013) packet.getInt(); // rate, gone after 10.19
                if (readString(packet) == null) return null; // name
                if (protocol >= 0xa0011) {
                    packet.get(); // clan tag position
                    if (readString(packet) == null) return null; // clan tag
                }
                packet.get(); // bot flag
                players++;
            }
        } catch (BufferUnderflowException e) {
            return null;
        }
    }

    /** Reads a NUL-terminated string; null if the packet ends before the NUL. */
    private static String readString(ByteBuffer packet) {
        int start = packet.position();
        for (int i = start; i < packet.limit(); i++) {
            if (packet.get(i) == 0) {
                String value = new String(packet.array(), start, i - start, StandardCharsets.ISO_8859_1);
                packet.position(i + 1);
                return value;
            }
        }
        return null;
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static String configured(Configuration configuration, String path) {
        return configuration.underlying().hasPath(path) ? configuration.underlying().getString(path) : "";
    }

    private static int intConfigured(Configuration configuration, String path) {
        // Values substituted from environment variables (${?VAR}) are always
        // typed as STRING by Typesafe Config, even when numeric, so getInt()
        // would throw ConfigException.WrongType here; parse it ourselves.
        if (!configuration.underlying().hasPath(path)) return 0;
        try {
            return Integer.parseInt(configuration.underlying().getString(path).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
