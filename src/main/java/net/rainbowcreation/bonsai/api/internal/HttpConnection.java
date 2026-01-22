package net.rainbowcreation.bonsai.api.internal;

import net.rainbowcreation.bonsai.api.BonsApi;

import java.io.OutputStream;

import java.net.HttpURLConnection;
import java.net.URL;

import java.nio.charset.StandardCharsets;

import java.util.concurrent.CompletableFuture;


public class HttpConnection implements Connection {
    private final String baseUrl;

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
    }

    @Override
    public CompletableFuture<String> send(String op, String db, String table, String key, String payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder urlStr = new StringBuilder(baseUrl);
                if (db != null) urlStr.append("/").append(db);
                if (table != null) urlStr.append("/").append(table);
                if (key != null) urlStr.append("/").append(key);

                URL url = new URL(urlStr.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(op);

                if (payload != null) {
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload.getBytes(StandardCharsets.UTF_8));
                    }
                }
                return "{}";
            } catch (Exception e) { throw new RuntimeException(e); }
        }, BonsApi.WORKER_POOL);
    }

    @Override
    public void stop() { BonsApi.WORKER_POOL.shutdown(); }
}