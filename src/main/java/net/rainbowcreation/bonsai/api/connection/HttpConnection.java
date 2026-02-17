package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.util.ThreadUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpConnection implements Connection {

    private final String baseUrl;
    private final ExecutorService executor;

    public HttpConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.executor = Executors.newCachedThreadPool(
            ThreadUtil.createThreadFactory("Bonsai-HTTP-IO", true)
        );
    }

    @Override
    public CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
        // HTTP transport with ID mode not fully implemented
        // This would require resolving IDs back to names or using a different HTTP API
        CompletableFuture<byte[]> failed = new CompletableFuture<>();
        failed.completeExceptionally(new UnsupportedOperationException(
            "HttpConnection with ID mode not supported. Use TcpConnection instead."));
        return failed;
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