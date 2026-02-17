package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.config.Config;
import net.rainbowcreation.bonsai.util.Stoppable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
public class ConnectionPool implements Connection, Stoppable {

    private final List<TcpConnection> connections;
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final int poolSize;
    private final AtomicInteger globalId = new AtomicInteger(0);

    public ConnectionPool(String host, int port) {
        this(host, port, Config.POOL_SIZE);
    }

    public ConnectionPool(String host, int port, int poolSize) {
        this.poolSize = poolSize;
        this.connections = new ArrayList<>(poolSize);

        for (int i = 0; i < poolSize; i++) {
            connections.add(new TcpConnection(host, port, globalId));
        }

        BonsApi.LOGGER.info("Created pool with " + poolSize + " connections to " + host + ":" + port);
    }

    public TcpConnection acquire() {
        int idx = Math.abs(roundRobin.getAndIncrement() % poolSize);
        TcpConnection conn = connections.get(idx);

        if (conn.getPendingCount() > Config.PIPELINE_MAX_PENDING / 2) {
            int minPending = conn.getPendingCount();
            TcpConnection best = conn;

            for (TcpConnection c : connections) {
                int pending = c.getPendingCount();
                if (pending < minPending) {
                    minPending = pending;
                    best = c;
                }
            }
            return best;
        }

        return conn;
    }

    @Override
    public CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags) {
        return acquire().send(op, dbId, tableId, key, payload, flags);
    }

    @Override
    public void setInvalidationCallback(InvalidationCallback callback) {
        for (TcpConnection conn : connections) {
            conn.setInvalidationCallback(callback);
        }
    }

    @Override
    public void stop() {
        for (TcpConnection conn : connections) {
            conn.stop();
        }
    }

    public int getTotalPendingCount() {
        int total = 0;
        for (TcpConnection conn : connections) {
            total += conn.getPendingCount();
        }
        return total;
    }

    public String getStats() {
        StringBuilder sb = new StringBuilder("ConnectionPool[");
        for (int i = 0; i < connections.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("c").append(i).append("=").append(connections.get(i).getPendingCount());
        }
        sb.append("]");
        return sb.toString();
    }
}
