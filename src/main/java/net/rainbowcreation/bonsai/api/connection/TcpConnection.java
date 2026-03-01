package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.auth.BonsaiAuth;
import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.BonsaiRequest;
import net.rainbowcreation.bonsai.BonsaiResponse;
import net.rainbowcreation.bonsai.api.config.Config;
import net.rainbowcreation.bonsai.api.util.ClientProfiler;
import net.rainbowcreation.bonsai.util.ThreadUtil;

import java.io.*;

import java.net.Socket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpConnection implements Connection {
    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final AtomicInteger idGen;
    private final Map<Integer, CompletableFuture<byte[]>> pendingRequests = new ConcurrentHashMap<>();

    private final Semaphore pipelineLimit = new Semaphore(Config.PIPELINE_MAX_PENDING);

    private byte[] writeBuffer = new byte[Config.WRITE_FLUSH_THRESHOLD];
    private int writePosition = 0;
    private final Object writeLock = new Object();
    private final ScheduledExecutorService flusher;
    private final AtomicInteger pendingBytes = new AtomicInteger(0);
    private final AtomicInteger bufferedRequestCount = new AtomicInteger(0);

    private final byte[] flushBuffer = new byte[Config.WRITE_FLUSH_THRESHOLD];

    private volatile boolean running = false;
    private volatile InvalidationCallback invalidationCallback;
    private volatile byte[] sessionNonce;

    public TcpConnection(String host, int port, AtomicInteger idGen) {
        this.host = host;
        this.port = port;
        this.idGen = idGen;
        this.flusher = Executors.newSingleThreadScheduledExecutor(
            ThreadUtil.createThreadFactory("Bonsai-Flusher", true)
        );
        connect();
    }

    private synchronized void connect() {
        if (running) return;
        try {
            closeQuietly();
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true);
            this.socket.setKeepAlive(true);
            this.socket.setSendBufferSize(Config.SOCKET_SEND_BUFFER);
            this.socket.setReceiveBufferSize(Config.SOCKET_RECEIVE_BUFFER);

            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
            this.out = new DataOutputStream(socket.getOutputStream());

            // Read server handshake: [1 byte mode][8 bytes nonce]
            byte mode = in.readByte();
            byte[] nonce = new byte[8];
            in.readFully(nonce);
            this.sessionNonce = nonce;

            if (mode == 0x01) {
                byte[] hmac = BonsaiAuth.computeHmac(Config.DB_PASSWORD, nonce, "");
                out.write(hmac);
                out.flush();
            }

            this.running = true;

            Thread t = ThreadUtil.newDaemonThread(this::readLoop, "Bonsai-Client-Reader");
            t.start();
            BonsApi.LOGGER.info("Connected to " + host + ":" + port);
        } catch (Exception e) {
            BonsApi.LOGGER.severe("Connection failed: " + e.getMessage());
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

                ByteBuffer buf = ByteBuffer.wrap(data);
                int receivedId = buf.getInt();

                if (receivedId == -1) {
                    BonsaiRequest push = BonsaiRequest.fromBytes(data);
                    
                    if ((push.op == RequestOp.INVALIDATE || push.op == RequestOp.CHANGE_EVENT) && invalidationCallback != null) {
                        invalidationCallback.onInvalidate(push.db, push.table, push.key);
                    }
                    continue;
                }

                BonsaiResponse res = BonsaiResponse.fromBytes(data);
                CompletableFuture<byte[]> future = pendingRequests.remove(res.id);

                ClientProfiler.onResponse(res.id);
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

                BonsApi.LOGGER.severe("Stream Error: " + e.getMessage());
                shutdownPending(e);

                reconnect();
                return;
            }
        }
    }

    private void flushBuffer() {
        if (pendingBytes.get() > 0) {
            try {
                doFlushFast();
            } catch (IOException e) {
                BonsApi.LOGGER.severe("Flush error: " + e.getMessage());
            }
        }
    }

    private void doFlushFast() throws IOException {
        if (out == null) return;

        int bytesToWrite;
        synchronized (writeLock) {
            if (writePosition == 0) return;

            System.arraycopy(writeBuffer, 0, flushBuffer, 0, writePosition);
            bytesToWrite = writePosition;

            writePosition = 0;
            pendingBytes.set(0);
            bufferedRequestCount.set(0);
        }

        out.write(flushBuffer, 0, bytesToWrite);
        out.flush();
    }

    private void doFlush() throws IOException {
        if (writePosition > 0 && out != null) {
            out.write(writeBuffer, 0, writePosition);
            out.flush();
            writePosition = 0;
            pendingBytes.set(0);
            bufferedRequestCount.set(0);
        }
    }

    private void reconnect() {
        running = false;
        pipelineLimit.release(Config.PIPELINE_MAX_PENDING);
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        pipelineLimit.drainPermits();
        for (int i = 0; i < Config.PIPELINE_MAX_PENDING; i++) {
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
    public CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
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

        int reqId = idGen.incrementAndGet();
        BonsaiRequest req = new BonsaiRequest(op, dbId, tableId, key, payload, flags);
        req.id = reqId;

        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingRequests.put(reqId, future);

        try {
            int dataLen = req.getSerializedSize();
            int totalSize = 4 + dataLen;

            synchronized (writeLock) {
                if (!running || out == null) throw new IOException("Not connected");

                
                if (writePosition + totalSize > writeBuffer.length) {
                    doFlush();
                }

                
                writeBuffer[writePosition++] = (byte) (dataLen >>> 24);
                writeBuffer[writePosition++] = (byte) (dataLen >>> 16);
                writeBuffer[writePosition++] = (byte) (dataLen >>> 8);
                writeBuffer[writePosition++] = (byte) dataLen;

                int written = req.writeTo(writeBuffer, writePosition);
                writePosition += written;
                pendingBytes.addAndGet(totalSize);

                doFlush();
            }
        } catch (Exception e) {
            pendingRequests.remove(reqId);
            pipelineLimit.release();
            future.completeExceptionally(e);
        }

        return future;
    }


    @Override
    public void stop() {
        running = false;
        flusher.shutdown();
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

    @Override
    public void setInvalidationCallback(InvalidationCallback callback) {
        this.invalidationCallback = callback;
    }

    public byte[] getNonce() {
        return sessionNonce;
    }

    public int getPendingCount() {
        return pendingRequests.size();
    }
}
