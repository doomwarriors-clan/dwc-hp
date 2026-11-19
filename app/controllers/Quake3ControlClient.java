package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import play.api.Configuration;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Talks to the quake3ctl sidecar (deploy/quake3ctl.py), a small root-owned
 * process that is the only thing on the host allowed to start/stop the
 * Quake 3 and ET:QW docker-compose stacks and to move files uploaded over SFTP into
 * the game servers' and the download section's folders. The dwc web process
 * itself has no Docker, systemd or such file privileges; it only ever calls
 * this fixed, token-authenticated HTTP endpoint on localhost.
 */
@Singleton
public final class Quake3ControlClient {
    public enum Server {
        DUEL("duel", "Duel"), TDM("tdm", "TDM"), CTF("ctf", "CTF"), INSTAGIB("instagib", "Instagib"),
        // Not a Quake 3 server: doomwarriors-clan/etqw-server on the same host (etqw.service).
        ETQW("etqw", "ET:QW");

        public final String id;
        public final String label;

        Server(String id, String label) {
            this.id = id;
            this.label = label;
        }

        static Server byId(String id) {
            for (Server server : values()) if (server.id.equals(id)) return server;
            return null;
        }
    }

    public record Status(Server server, String state, String error) {
        public boolean active() {
            return "active".equals(state);
        }
    }

    /** A file in the SFTP upload folder; replaces maps target names to the file it would overwrite there. */
    public record Upload(String name, long size, Instant modified, Map<String, Existing> replaces) { }

    public record Existing(long size, Instant modified) { }

    public record Uploads(List<Upload> uploads, String error) { }

    private final WSClient ws;
    private final String baseUrl;
    private final String token;

    @Inject
    Quake3ControlClient(WSClient ws, Configuration configuration) {
        this.ws = ws;
        this.baseUrl = configured(configuration, "quake3.control.baseUrl");
        this.token = configured(configuration, "quake3.control.token");
    }

    boolean configured() {
        return !baseUrl.isBlank() && !token.isBlank();
    }

    CompletionStage<List<Status>> statusAll() {
        List<CompletionStage<Status>> futures = Arrays.stream(Server.values()).map(this::status).toList();
        return CompletableFuture.allOf(futures.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletionStage::toCompletableFuture)
                        .map(CompletableFuture::join).toList());
    }

    CompletionStage<Status> status(Server server) {
        return request("/status/" + server.id).get()
                .handle((response, error) -> toStatus(server, response, error));
    }

    CompletionStage<Status> start(Server server) {
        return request("/start/" + server.id).post("")
                .handle((response, error) -> toStatus(server, response, error));
    }

    CompletionStage<Status> stop(Server server) {
        return request("/stop/" + server.id).post("")
                .handle((response, error) -> toStatus(server, response, error));
    }

    CompletionStage<Uploads> uploads() {
        return request("/uploads").get().handle((response, error) -> {
            String failure = failure(response, error);
            return failure != null ? new Uploads(List.of(), failure) : new Uploads(parseUploads(response.getBody()), null);
        });
    }

    /** Null on success, otherwise why the file could not be installed. */
    CompletionStage<String> installUpload(String name, String target) {
        return request("/uploads/" + name + "/install/" + target).post("").handle(Quake3ControlClient::failure);
    }

    /** Null on success, otherwise why the file could not be discarded. */
    CompletionStage<String> discardUpload(String name) {
        return request("/uploads/" + name + "/discard").post("").handle(Quake3ControlClient::failure);
    }

    static List<Upload> parseUploads(String json) {
        List<Upload> uploads = new ArrayList<>();
        for (JsonNode node : Json.parse(json)) {
            Map<String, Existing> replaces = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> targets = node.path("replaces").fields();
            while (targets.hasNext()) {
                Map.Entry<String, JsonNode> target = targets.next();
                replaces.put(target.getKey(), new Existing(target.getValue().path("size").asLong(),
                        Instant.ofEpochSecond(target.getValue().path("mtime").asLong())));
            }
            uploads.add(new Upload(node.path("name").asText(), node.path("size").asLong(),
                    Instant.ofEpochSecond(node.path("mtime").asLong()), replaces));
        }
        return uploads;
    }

    private static String failure(WSResponse response, Throwable error) {
        if (error != null) return "Control service unreachable.";
        if (response.getStatus() != 200) return "Control service error: " + response.getBody().trim();
        return null;
    }

    private Status toStatus(Server server, WSResponse response, Throwable error) {
        String failure = failure(response, error);
        if (failure != null) return new Status(server, null, failure);
        return new Status(server, response.getBody().trim(), null);
    }

    private WSRequest request(String path) {
        return ws.url(baseUrl + path)
                .addHeader("Authorization", "Bearer " + token)
                .setRequestTimeout(Duration.ofSeconds(65));
    }

    private static String configured(Configuration configuration, String path) {
        return configuration.underlying().hasPath(path) ? configuration.underlying().getString(path) : "";
    }
}
