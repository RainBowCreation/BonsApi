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

    private volatile boolean running = false;
    private final Object writeLock = new Object();

    public TcpConnection(String host, int port) {
        this.host = host;
        this.port = port;
        connect();
    }

    private synchronized void connect() {
        if (running) return;
        try {
            closeQuietly();
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true);
            this.socket.setKeepAlive(true);

            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            this.running = true;

            Thread t = new Thread(this::readLoop, "Bonsai-Client-Reader");
            t.setDaemon(true);
            t.start();
            System.out.println("[TcpConnection] Connected to " + host + ":" + port);
        } catch (Exception e) {
            System.err.println("[TcpConnection] Connection failed: " + e.getMessage());
        }
    }

    private void readLoop() {
        Socket mySocket = this.socket;

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (mySocket != this.socket) return;

                int len = in.readInt();
                if (len < 0) throw new IOException("Invalid frame length");

                byte[] data = new byte[len];
                in.readFully(data);

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

                System.err.println("[TcpConnection] Stream Error: " + e.getMessage());
                shutdownPending(e);

                reconnect();
                return;
            }
        }
    }

    private void reconnect() {
        running = false; // Stop everything
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        connect(); // Start new connection & new thread
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
            byte[] data = req.getBytes();

            synchronized (writeLock) {
                if (!running || out == null) throw new IOException("Not connected");
                out.writeInt(data.length);
                out.write(data);
                out.flush();
            }
        } catch (Exception e) {
            pendingRequests.remove(reqId);
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void stop() {
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}