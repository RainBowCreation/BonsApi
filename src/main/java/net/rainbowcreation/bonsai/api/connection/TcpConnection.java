package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.api.BonsaiRequest;
import net.rainbowcreation.bonsai.api.BonsaiResponse;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TcpConnection implements Connection {
    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final AtomicLong idGen = new AtomicLong(0);
    private final Map<Long, CompletableFuture<byte[]>> pendingRequests = new ConcurrentHashMap<>();
    private Thread readerThread;
    private volatile boolean running = false;
    private final Object writeLock = new Object();

    public TcpConnection(String host, int port) {
        this.host = host;
        this.port = port;
        connect();
    }

    private void connect() {
        if (running) return;
        try {
            closeQuietly();
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true); // Critical for low latency
            this.socket.setKeepAlive(true);

            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            this.running = true;
            this.readerThread = new Thread(this::readLoop, "Bonsai-Client-Reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to Bonsai Sidecar", e);
        }
    }

    private void readLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // 1. Read Frame Length
                int len = in.readInt();
                if (len < 0) throw new IOException("Invalid frame length");

                // 2. Read Frame Data
                byte[] data = new byte[len];
                in.readFully(data);

                // 3. Manual Deserialization (No Fory)
                BonsaiResponse res = BonsaiResponse.fromBytes(data);

                CompletableFuture<byte[]> future = pendingRequests.remove(res.id);

                if (future != null) {
                    if (res.status >= 400) {
                        String msg = (res.body != null) ? new String(res.body, StandardCharsets.UTF_8) : "Unknown Error";
                        future.completeExceptionally(new RuntimeException("Bonsai Error (" + res.status + "): " + msg));
                    } else {
                        future.complete(res.body);
                    }
                }
            } catch (Exception e) {
                if (!running) break;
                shutdownPending(e);
                reconnect();
            }
        }
    }

    private void reconnect() {
        running = false;
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        connect();
    }

    private void shutdownPending(Throwable t) {
        for (CompletableFuture<byte[]> f : pendingRequests.values()) {
            f.completeExceptionally(t);
        }
        pendingRequests.clear();
    }

    @Override
    public CompletableFuture<byte[]> send(RequestOp op, String db, String table, String key, byte[] payload) {
        if (!running) connect();

        long reqId = idGen.incrementAndGet();
        BonsaiRequest req = new BonsaiRequest(op, db, table, key, payload);
        req.id = reqId;

        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingRequests.put(reqId, future);

        try {
            // 1. Manual Serialization
            byte[] data = req.getBytes();

            synchronized (writeLock) {
                out.writeInt(data.length); // Write Header
                out.write(data);           // Write Body
                out.flush();
            }
        } catch (Exception e) {
            pendingRequests.remove(reqId);
            future.completeExceptionally(e);
            running = false;
        }

        return future;
    }

    @Override
    public void stop() {
        running = false;
        if (readerThread != null) readerThread.interrupt();
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}