package net.rainbowcreation.bonsai.api.internal;

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
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true);
            this.socket.setSoTimeout(5000);

            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to Bonsai Sidecar at " + host + ":" + port, e);
        }
    }

    @Override
    public CompletableFuture<String> send(String op, String db, String table, String key, String payload) {
        return CompletableFuture.supplyAsync(() -> {
            lock.lock(); // Ensure only one thread writes to the socket at a time
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) {
                    connect();
                }

                // --- WRITE ---
                // Protocol: [OP_LEN][OP] [DB_LEN][DB] [TBL_LEN][TBL] [KEY_LEN][KEY] [PAY_LEN][PAY]
                writeString(op);
                writeString(db);
                writeString(table);
                writeString(key);
                writeString(payload);
                out.flush();

                // --- READ ---
                // Protocol: [STATUS_CODE] [BODY_LEN] [BODY]
                int status = in.readInt();
                String responseBody = readString();

                if (status >= 400) {
                    throw new RuntimeException("Bonsai Error (" + status + "): " + responseBody);
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
            out.writeInt(-1); // -1 indicates NULL
        } else {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private String readString() throws Exception {
        int length = in.readInt();
        if (length == -1) return null;

        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}