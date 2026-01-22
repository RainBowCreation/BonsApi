package net.rainbowcreation.bonsai.api.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.util.concurrent.CompletableFuture;

public class HttpConnection implements Connection {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
    }

    @Override
    public CompletableFuture<String> send(String op, String db, String table, String key, String payload) {
        StringBuilder urlStr = new StringBuilder(baseUrl);
        if (db != null) urlStr.append("/").append(db);
        if (table != null) urlStr.append("/").append(table);
        if (key != null) urlStr.append("/").append(key);

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(urlStr.toString()));
        HttpRequest.BodyPublisher body = (payload == null) ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload);

        switch (op) {
            case "GET": builder.GET(); break;
            case "DELETE": builder.DELETE(); break;
            default: builder.method(op, body); break;
        }

        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    @Override
    public void stop() {}
}
