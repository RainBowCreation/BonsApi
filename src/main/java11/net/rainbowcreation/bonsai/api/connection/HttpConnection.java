package net.rainbowcreation.bonsai.api.connection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class HttpConnection implements Connection {

    private final String baseUrl;
    private final HttpClient client;

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public CompletableFuture<byte[]> send(RequestOp op, String db, String table, String key, byte[] payload) {
        StringBuilder urlStr = new StringBuilder(baseUrl)
                .append("/v1/data/")
                .append(db).append("/")
                .append(table);

        if (key != null) urlStr.append("/").append(key);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(urlStr.toString()))
                .header("User-Agent", "Bonsai-Twig/1.0")
                .header("X-Bonsai-Op", op.toString())
                .timeout(Duration.ofSeconds(10));

        if (payload != null) {
            rb.POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            rb.header("Content-Type", "application/octet-stream");
        } else {
            rb.GET();
        }

        return client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(res -> {
                    if (res.statusCode() >= 400) {
                        String msg = new String(res.body());
                        throw new RuntimeException("HTTP " + res.statusCode() + ": " + msg);
                    }
                    return res.body();
                });
    }

    @Override
    public void stop() {
    }
}