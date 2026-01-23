package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.api.BonsApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class HttpConnection implements Connection {

    private final HttpClient client;
    private final String baseUrl;

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(BonsApi.WORKER_POOL)
                .build();
    }

    @Override
    public CompletableFuture<byte[]> send(String op, String db, String table, String key, byte[] payload) {
        StringBuilder urlStr = new StringBuilder(baseUrl)
                .append("/v1/data/")
                .append(db).append("/")
                .append(table);

        if (key != null) urlStr.append("/").append(key);

        HttpRequest.BodyPublisher bodyPublisher = (payload == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlStr.toString()))
                .header("User-Agent", "Bonsai-Twig/1.0")
                .header("X-Bonsai-Op", op)
                .timeout(Duration.ofSeconds(10));

        if (payload != null) {
            builder.POST(bodyPublisher);
            builder.header("Content-Type", "application/octet-stream");
        } else {
            builder.GET();
        }

        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    int status = response.statusCode();

                    if (status >= 400) {
                        String error = new String(response.body());
                        throw new RuntimeException("HTTP " + status + ": " + error);
                    }

                    return response.body();
                });
    }

    @Override
    public void stop() {}
}
