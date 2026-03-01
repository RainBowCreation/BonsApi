package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.api.config.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.time.Duration;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class HttpConnection implements Connection {

    private final String baseUrl;
    private final HttpClient client;
    private final String bearerToken;

    private final Map<Short, String> dbNames = new ConcurrentHashMap<>();
    private final Map<Long, String> tableNames = new ConcurrentHashMap<>();

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port + "/v1/data";
        this.bearerToken = Config.DB_PASSWORD;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
        if (op == RequestOp.SUBSCRIBE) {
            return CompletableFuture.completedFuture(new byte[0]);
        }

        final String dbName, tableName;
        try {
            if (op == RequestOp.REGISTER_SCHEMA) {
                dbName = (key != null && !key.isEmpty()) ? key : "";
                String t = extractTableName(payload);
                tableName = (t == null || t.isEmpty()) ? "_" : t;
            } else {
                String d = dbNames.get(dbId);
                String t = tableNames.get(packIds(dbId, tableId));
                if (d == null || t == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                        "HttpConnection: no name mapping for dbId=" + dbId + " tableId=" + tableId +
                        ". Call REGISTER_SCHEMA first (via RemoteRoot.use(...))."));
                }
                dbName = d;
                tableName = t;
            }
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        // URL: /v1/data/{db}/{table}  — key is sent via X-Bonsai-Key header
        String urlStr = baseUrl + "/" + dbName + "/" + tableName;

        HttpRequest.BodyPublisher bodyPublisher = (payload != null && payload.length > 0)
                ? HttpRequest.BodyPublishers.ofByteArray(payload)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/octet-stream")
                .header("X-Bonsai-Op", op.getSymbol())
                .header("X-Bonsai-Flags", String.valueOf(flags & 0xFF))
                .method("POST", bodyPublisher);

        boolean hasKey = key != null && !key.isEmpty()
                && op != RequestOp.MGET
                && !op.getSymbol().startsWith("QUERY_")
                && op != RequestOp.REGISTER_SCHEMA;
        if (hasKey) {
            builder.header("X-Bonsai-Key", key);
        }
        if (!bearerToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    int status = resp.statusCode();
                    byte[] respBody = resp.body();
                    if (status >= 400) {
                        String msg = (respBody != null && respBody.length > 0)
                                ? new String(respBody, StandardCharsets.UTF_8) : "HTTP " + status;
                        throw new RuntimeException("Bonsai HTTP Error (" + status + "): " + msg);
                    }

                    if (op == RequestOp.REGISTER_SCHEMA && respBody != null && respBody.length >= 3) {
                        ByteBuffer buf = ByteBuffer.wrap(respBody);
                        short respDbId = (short) (buf.get() & 0xFF);
                        short respTableId = buf.getShort();
                        dbNames.putIfAbsent(respDbId, dbName);
                        if (!"_".equals(tableName)) {
                            tableNames.putIfAbsent(packIds(respDbId, respTableId), tableName);
                        }
                    }

                    return (respBody != null) ? respBody : new byte[0];
                });
    }

    /** Extracts the "table" value from JSON payload: {"table":"name","columns":[...]} */
    private String extractTableName(byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        String json = new String(payload, StandardCharsets.UTF_8);
        int idx = json.indexOf("\"table\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + 7);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private long packIds(short dbId, short tableId) {
        return ((long) (dbId & 0xFFFF) << 16) | (tableId & 0xFFFF);
    }

    @Override
    public void stop() {
    }
}
