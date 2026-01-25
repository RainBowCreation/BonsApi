package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.api.BonsaiRequest;
import net.rainbowcreation.bonsai.api.BonsaiResponse;
import net.rainbowcreation.bonsai.api.util.ForyFactory;
import org.apache.fory.ThreadSafeFory;

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

                BonsaiRequest req = new BonsaiRequest(op, db, table, key, payload);

                ThreadSafeFory fory = ForyFactory.get();
                byte[] requestBytes = fory.serialize(req);

                out.writeInt(requestBytes.length);
                out.write(requestBytes);
                out.flush();

                int len = in.readInt();
                if (len < 0) throw new RuntimeException("Invalid response frame length");

                byte[] responseBytes = new byte[len];
                in.readFully(responseBytes);

                // 5. Deserialize with Fory
                BonsaiResponse res = (BonsaiResponse) fory.deserialize(responseBytes);

                if (res.status >= 400) {
                    String errorMsg = (res.body != null) ? new String(res.body, StandardCharsets.UTF_8) : "Unknown Error";
                    throw new RuntimeException("Bonsai Error (" + res.status + "): " + errorMsg);
                }

                return res.body;

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

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}