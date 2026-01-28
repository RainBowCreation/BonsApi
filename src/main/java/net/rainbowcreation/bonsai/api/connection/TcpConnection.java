package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.api.BonsaiRequest;
import net.rainbowcreation.bonsai.api.BonsaiResponse;
import net.rainbowcreation.bonsai.api.config.ClientConfig;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TcpConnection implements Connection {
    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final AtomicLong idGen = new AtomicLong(0);
    private final Map<Long, CompletableFuture<byte[]>> pendingRequests = new ConcurrentHashMap<>();

    private final Semaphore pipelineLimit = new Semaphore(ClientConfig.PIPELINE_MAX_PENDING);

    private final ByteArrayOutputStream writeBuffer = new ByteArrayOutputStream(ClientConfig.WRITE_FLUSH_THRESHOLD);
    private final Object writeLock = new Object();
    private final ScheduledExecutorService flusher;
    private final AtomicInteger pendingBytes = new AtomicInteger(0);

    private volatile boolean running = false;

    public TcpConnection(String host, int port) {
        this.host = host;
        this.port = port;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Bonsai-Flusher");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flushBuffer,
                ClientConfig.WRITE_FLUSH_INTERVAL_MS,
                ClientConfig.WRITE_FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        connect();
    }

    private synchronized void connect() {
        if (running) return;
        try {
            closeQuietly();
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true);
            this.socket.setKeepAlive(true);
            this.socket.setSendBufferSize(ClientConfig.SOCKET_SEND_BUFFER);
            this.socket.setReceiveBufferSize(ClientConfig.SOCKET_RECEIVE_BUFFER);

            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), ClientConfig.WRITE_FLUSH_THRESHOLD));

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

                pipelineLimit.release();

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
        running = false;
        // Release all blocked senders
        pipelineLimit.release(ClientConfig.PIPELINE_MAX_PENDING);
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        // Reset semaphore
        pipelineLimit.drainPermits();
        for (int i = 0; i < ClientConfig.PIPELINE_MAX_PENDING; i++) {
            pipelineLimit.release();
        }
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

        try {
            if (!pipelineLimit.tryAcquire(5, TimeUnit.SECONDS)) {
                CompletableFuture<byte[]> failed = new CompletableFuture<>();
                failed.completeExceptionally(new TimeoutException("Pipeline full"));
                return failed;
            }
        } catch (InterruptedException e) {
            CompletableFuture<byte[]> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }

        long reqId = idGen.incrementAndGet();
        BonsaiRequest req = new BonsaiRequest(op, db, table, key, payload);
        req.id = reqId;

        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingRequests.put(reqId, future);

        try {
            byte[] data = req.getBytes();

            synchronized (writeLock) {
                if (!running || out == null) throw new IOException("Not connected");

                writeBuffer.write((data.length >> 24) & 0xFF);
                writeBuffer.write((data.length >> 16) & 0xFF);
                writeBuffer.write((data.length >> 8) & 0xFF);
                writeBuffer.write(data.length & 0xFF);
                writeBuffer.write(data);

                int buffered = pendingBytes.addAndGet(4 + data.length);

                if (buffered >= ClientConfig.WRITE_FLUSH_THRESHOLD || pendingRequests.size() <= 2) {
                    doFlush();
                }
            }
        } catch (Exception e) {
            pendingRequests.remove(reqId);
            pipelineLimit.release();
            future.completeExceptionally(e);
        }

        return future;
    }

    private void flushBuffer() {
        if (pendingBytes.get() > 0) {
            synchronized (writeLock) {
                if (pendingBytes.get() > 0) {
                    try {
                        doFlush();
                    } catch (IOException e) {
                        System.err.println("[TcpConnection] Flush error: " + e.getMessage());
                    }
                }
            }
        }
    }

    private void doFlush() throws IOException {
        if (writeBuffer.size() > 0 && out != null) {
            out.write(writeBuffer.toByteArray());
            out.flush();
            writeBuffer.reset();
            pendingBytes.set(0);
        }
    }

    @Override
    public void stop() {
        running = false;
        flusher.shutdown();
        // Final flush
        synchronized (writeLock) {
            try {
                doFlush();
            } catch (IOException ignored) {}
        }
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    public int getPendingCount() {
        return pendingRequests.size();
    }
}
