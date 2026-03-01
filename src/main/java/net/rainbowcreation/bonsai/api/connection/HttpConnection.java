package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.api.config.Config;
import net.rainbowcreation.bonsai.util.ThreadUtil;

import java.io.*;

import java.net.HttpURLConnection;
import java.net.URL;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.util.Map;
import java.util.concurrent.*;

/**
 * HTTP-based connection to a Bonsai Edge server.
 *
 * <p>Uses binary (application/octet-stream) mode: serialization/deserialization
 * is performed entirely on the client side, matching the TCP transport.
 *
 * <p>Operations: GET, SET, DELETE, EXISTS, MGET, QUERY_GET/UPDATE/DELETE/COUNT,
 * REGISTER_SCHEMA. SUBSCRIBE is a no-op (HTTP has no server-push).
 *
 * <p>Auth: sends {@code Authorization: Bearer <bonsai.db.password>} when set.
 * <p>TTL: communicated via {@code X-Bonsai-Flags} header (bit 0x02) with the
 * 8-byte expiry timestamp prepended to the payload, matching the TCP protocol.
 */
public class HttpConnection implements Connection {

    private final String baseUrl;
    private final ExecutorService executor;
    private final String bearerToken;

    private final Map<Short, String> dbNames = new ConcurrentHashMap<>();
    private final Map<Long, String> tableNames = new ConcurrentHashMap<>();

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port + "/v1/data";
        this.bearerToken = Config.DB_PASSWORD;
        this.executor = Executors.newCachedThreadPool(
            ThreadUtil.createThreadFactory("Bonsai-HTTP-IO", true)
        );
    }

    @Override
    public CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
        return CompletableFuture.supplyAsync(() -> doSend(op, dbId, tableId, key, payload, flags), executor);
    }

    private byte[] doSend(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
        try {
            if (op == RequestOp.SUBSCRIBE) {
                return new byte[0];
            }

            final String dbName, tableName;
            if (op == RequestOp.REGISTER_SCHEMA) {
                dbName = (key != null && !key.isEmpty()) ? key : "";
                String t = extractTableName(payload);
                tableName = (t == null || t.isEmpty()) ? "_" : t;
            } else {
                String d = dbNames.get(dbId);
                String t = tableNames.get(packIds(dbId, tableId));
                if (d == null || t == null) {
                    throw new IllegalStateException(
                        "HttpConnection: no name mapping for dbId=" + dbId + " tableId=" + tableId +
                        ". Call REGISTER_SCHEMA first (via RemoteRoot.use(...)).");
                }
                dbName = d;
                tableName = t;
            }

            String urlStr = baseUrl + "/" + dbName + "/" + tableName;

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("X-Bonsai-Op", op.getSymbol());
            conn.setRequestProperty("X-Bonsai-Flags", String.valueOf(flags & 0xFF));

            boolean hasKey = key != null && !key.isEmpty()
                && op != RequestOp.MGET
                && !op.getSymbol().startsWith("QUERY_")
                && op != RequestOp.REGISTER_SCHEMA;
            if (hasKey) {
                conn.setRequestProperty("X-Bonsai-Key", key);
            }
            if (!bearerToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            if (payload != null && payload.length > 0) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            } else {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            byte[] body = readAll(is);

            if (status >= 400) {
                String msg = (body != null && body.length > 0)
                    ? new String(body, StandardCharsets.UTF_8) : "HTTP " + status;
                throw new RuntimeException("Bonsai HTTP Error (" + status + "): " + msg);
            }

            if (op == RequestOp.REGISTER_SCHEMA && body != null && body.length >= 3) {
                ByteBuffer buf = ByteBuffer.wrap(body);
                short respDbId = (short) (buf.get() & 0xFF);
                short respTableId = buf.getShort();
                dbNames.putIfAbsent(respDbId, dbName);
                if (!"_".equals(tableName)) {
                    tableNames.putIfAbsent(packIds(respDbId, respTableId), tableName);
                }
            }

            return (body != null) ? body : new byte[0];

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed for op=" + op.getSymbol(), e);
        }
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

    private byte[] readAll(InputStream is) throws IOException {
        if (is == null) return null;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Override
    public void stop() {
        executor.shutdown();
    }
}
