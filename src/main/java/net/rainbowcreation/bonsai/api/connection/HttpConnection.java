package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.api.BonsApi;

import java.io.OutputStream;

import java.net.HttpURLConnection;
import java.net.URL;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

public class HttpConnection implements Connection {

    private final String baseUrl;

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
    }

    @Override
    public CompletableFuture<byte[]> send(String op, String db, String table, String key, byte[] payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // /v1/data/{db}/{table}/ *{key}*
                StringBuilder urlStr = new StringBuilder(baseUrl)
                        .append("/v1/data/")
                        .append(db).append("/")
                        .append(table);

                if (key != null) urlStr.append("/").append(key);

                URL url = new URL(urlStr.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestProperty("User-Agent", "Bonsai-Twig/1.0");
                conn.setRequestProperty("X-Bonsai-Op", op);

                // Output (Request Body)
                if (payload != null) {
                    conn.setRequestMethod("POST"); // Use POST for data transport
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

                if (status >= 400) {
                    try (InputStream es = conn.getErrorStream()) {
                        byte[] errBytes = readAll(es);
                        String errMsg = (errBytes != null) ? new String(errBytes) : "Unknown HTTP Error";
                        throw new RuntimeException("HTTP " + status + ": " + errMsg);
                    }
                }

                try (InputStream is = conn.getInputStream()) {
                    return readAll(is);
                }

            } catch (Exception e) {
                throw new RuntimeException("HTTP Transport Error", e);
            }
        }, BonsApi.WORKER_POOL);
    }

    @Override
    public void stop() {}

    private byte[] readAll(InputStream is) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[4096]; // 4kb buffer
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }
}