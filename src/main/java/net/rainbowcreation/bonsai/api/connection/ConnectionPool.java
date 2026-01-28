package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.config.ClientConfig;
import net.rainbowcreation.bonsai.api.util.Stoppable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A pool of TCP connections to distribute load across multiple connections.
 * Uses round-robin selection with least-pending fallback for load balancing.
 */
public class ConnectionPool implements Connection, Stoppable {

    private final List<TcpConnection> connections;
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final int poolSize;
    private final AtomicLong globalId = new AtomicLong(0);

    public ConnectionPool(String host, int port) {
        this(host, port, ClientConfig.POOL_SIZE);
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
        // Start with round-robin
        int idx = Math.abs(roundRobin.getAndIncrement() % poolSize);
        TcpConnection conn = connections.get(idx);

        // If selected connection has too many pending, find one with fewer
        if (conn.getPendingCount() > ClientConfig.PIPELINE_MAX_PENDING / 2) {
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
    public CompletableFuture<byte[]> send(RequestOp op, String db, String table, String key, byte[] payload) {
        return acquire().send(op, db, table, key, payload);
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
