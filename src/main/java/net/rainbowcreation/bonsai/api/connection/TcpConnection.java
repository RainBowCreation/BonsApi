package net.rainbowcreation.bonsai.api.connection;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import java.io.IOException;
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
            closeQuietly();
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true); // Ultra-Fast Mode
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
            lock.lock();
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) {
                    connect();
                    if (socket == null) throw new RuntimeException("Unable to connect to Bonsai Sidecar");
                }

                ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
                DataOutputStream body = new DataOutputStream(buffer);

                writeString(body, op);
                writeString(body, db);
                writeString(body, table);
                writeString(body, key);
                writeBlob(body, payload);

                byte[] packet = buffer.toByteArray();

                out.writeInt(packet.length);
                out.write(packet);
                out.flush();

                int frameLen = in.readInt();
                int status = in.readInt();
                byte[] responseBody = readBlob(in);

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

    private void writeString(DataOutputStream d, String str) throws IOException {
        if (str == null) {
            d.writeInt(-1);
        } else {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            d.writeInt(bytes.length);
            d.write(bytes);
        }
    }

    private void writeBlob(DataOutputStream d, byte[] bytes) throws IOException {
        if (bytes == null) {
            d.writeInt(-1);
        } else {
            d.writeInt(bytes.length);
            d.write(bytes);
        }
    }

    private byte[] readBlob(DataInputStream d) throws IOException {
        int length = d.readInt();
        if (length == -1) return null;
        if (length == 0) return new byte[0];

        byte[] bytes = new byte[length];
        d.readFully(bytes);
        return bytes;
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}