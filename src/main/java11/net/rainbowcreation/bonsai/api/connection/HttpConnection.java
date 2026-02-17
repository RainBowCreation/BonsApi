package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.connection.RequestOp;

import java.net.http.HttpClient;

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
    public CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
        // HTTP transport with ID mode not fully implemented
        // This would require resolving IDs back to names or using a different HTTP API
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
            "HttpConnection with ID mode not supported. Use TcpConnection instead."));
    }

    @Override
    public void stop() {
    }
}