package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.api.query.QueryOp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpConnection implements Connection {

    private final String baseUrl;
    private final ExecutorService executor;

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        // Dedicated pool for blocking I/O to prevent starvation of the main app/ForkJoin pool
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Bonsai-HTTP-IO");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletableFuture<byte[]> send(RequestOp op, String db, String table, String key, byte[] payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder urlStr = new StringBuilder(baseUrl)
                        .append("/v1/data/")
                        .append(db).append("/")
                        .append(table);

                if (key != null) urlStr.append("/").append(key);

                URL url = new URL(urlStr.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestProperty("User-Agent", "Bonsai-Twig/1.0");
                conn.setRequestProperty("X-Bonsai-Op", op.toString());
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);

                if (payload != null) {
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/octet-stream");
                    conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
                    conn.setDoOutput(true);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload);
                    }
                } else {
                    conn.setRequestMethod("GET");
                }

                int status = conn.getResponseCode();
                InputStream stream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();

                byte[] body = readAll(stream);

                if (status >= 400) {
                    String msg = (body != null) ? new String(body) : "Unknown HTTP Error";
                    throw new RuntimeException("HTTP " + status + ": " + msg);
                }

                return body;

            } catch (Exception e) {
                throw new RuntimeException("HTTP Transport Error", e);
            }
        }, executor);
    }

    @Override
    public void stop() {
        executor.shutdown();
    }

    private byte[] readAll(InputStream is) throws Exception {
        if (is == null) return null;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            int nRead;
            byte[] data = new byte[4096];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            return buffer.toByteArray();
        }
    }
}