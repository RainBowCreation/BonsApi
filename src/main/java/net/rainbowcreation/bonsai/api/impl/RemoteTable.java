package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.BonsaiFuture;
import net.rainbowcreation.bonsai.api.BonsaiTable;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.api.query.Query;
import org.apache.fory.ThreadSafeFory;

import java.util.concurrent.CompletableFuture;

public class RemoteTable<T> implements BonsaiTable<T> {
    private final Connection conn;
    private final String db, table;
    private final Class<T> type;
    private final ThreadSafeFory fory;

    public RemoteTable(Connection conn, String db, String table, Class<T> type, ThreadSafeFory fory) {
        this.conn = conn;
        this.db = db;
        this.table = table;
        this.type = type;
        this.fory = fory;
    }

    @Override
    public BonsaiFuture<T> getAsync(String key) {
        // Send: No payload needed for GET
        CompletableFuture<byte[]> io = conn.send("GET", db, table, key, null);

        CompletableFuture<T> safe = io.handleAsync((bytes, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            if (bytes == null || bytes.length == 0) return null; // 404 Not Found
            return (T) fory.deserialize(bytes);
        }, BonsApi.WORKER_POOL);

        return new BonsaiFuture<>(safe);
    }

    @Override
    public BonsaiFuture<Void> setAsync(String key, T value) {
        byte[] payload = fory.serialize(value);

        CompletableFuture<byte[]> io = conn.send("SET", db, table, key, payload);

        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) throw new RuntimeException(e);
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> deleteAsync(String key) {
        // DELETE has no payload
        CompletableFuture<byte[]> io = conn.send("DELETE", db, table, key, null);

        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) throw new RuntimeException(e);
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Boolean> existsAsync(String key) {
        // server returns "1" byte for true, empty or "0" for false
        return new BonsaiFuture<>(conn.send("EXISTS", db, table, key, null)
                .thenApplyAsync(bytes -> bytes != null && bytes.length > 0 && bytes[0] == '1', BonsApi.WORKER_POOL));
    }

    @Override
    public Query<T> find() {
        return new RemoteQuery<>(conn, db, table, type, fory);
    }
}