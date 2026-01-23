package net.rainbowcreation.bonsai.api.connection;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import java.net.Socket;

import java.nio.charset.StandardCharsets;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class TcpConnection implements Connection {

    private final String host;
    private final int port;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final ReentrantLock lock = new ReentrantLock();

    public TcpConnection(String host, int port) {
        this.host = host;
        this.port = port;
        connect();
    }

    private void connect() {
        try {
            // Close old socket if exists
            closeQuietly();

            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true); // Disable Nagle's Algorithm for lower latency
            this.socket.setSoTimeout(5000);

            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
        } catch (Exception e) {
            System.err.println("[Bonsai] TCP Connect Failed: " + e.getMessage());
        }
    }

    @Override
    public CompletableFuture<byte[]> send(String op, String db, String table, String key, byte[] payload) {
        return CompletableFuture.supplyAsync(() -> {
            lock.lock(); // CRITICAL: Only one thread can write to the socket stream at a time
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) {
                    connect();
                    if (socket == null) throw new RuntimeException("Unable to connect to Bonsai Sidecar");
                }

                // --- WRITE REQUEST ---
                // Protocol: [OP_LEN][OP] [DB_LEN][DB] [TBL_LEN][TBL] [KEY_LEN][KEY] [PAY_LEN][PAYLOAD]

                writeString(op);
                writeString(db);
                writeString(table);
                writeString(key);
                writeBlob(payload);

                out.flush();

                // --- READ RESPONSE ---
                // Protocol: [STATUS_INT] [BODY_LEN] [BODY_BYTES]

                int status = in.readInt();
                byte[] responseBody = readBlob();

                if (status >= 400) {
                    String errorMsg = (responseBody != null) ? new String(responseBody, StandardCharsets.UTF_8) : "Unknown Error";
                    throw new RuntimeException("Bonsai Error (" + status + "): " + errorMsg);
                }

                return responseBody;

            } catch (Exception e) {
                closeQuietly();
                throw new RuntimeException("TCP Transport Error", e);
            } finally {
                lock.unlock();
            }
        }, ioExecutor);
    }

    @Override
    public void stop() {
        ioExecutor.shutdown();
        closeQuietly();
    }

    private void writeString(String str) throws Exception {
        if (str == null) {
            out.writeInt(-1);
        } else {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private void writeBlob(byte[] bytes) throws Exception {
        if (bytes == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private byte[] readBlob() throws Exception {
        int length = in.readInt();
        if (length == -1) return null;
        if (length == 0) return new byte[0];

        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}