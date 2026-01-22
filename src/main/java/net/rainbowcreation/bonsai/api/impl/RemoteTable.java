package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.BonsaiFuture;
import net.rainbowcreation.bonsai.api.BonsaiTable;
import net.rainbowcreation.bonsai.api.internal.Connection;
import net.rainbowcreation.bonsai.api.query.Query;

import java.util.concurrent.CompletableFuture;

public class RemoteTable<T> implements BonsaiTable<T> {
    private final Connection conn;
    private final String db, table;
    private final Class<T> type;

    public RemoteTable(Connection conn, String db, String table, Class<T> type) {
        this.conn = conn; this.db = db; this.table = table; this.type = type;
    }

    @Override
    public BonsaiFuture<T> getAsync(String key) {
        CompletableFuture<String> io = conn.send("GET", db, table, key, null);

        CompletableFuture<T> safe = io.handleAsync((json, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            return null;
        }, BonsApi.WORKER_POOL);

        return new BonsaiFuture<>(safe);
    }

    @Override
    public BonsaiFuture<Void> setAsync(String key, T value) {
        String payload = "";
        CompletableFuture<String> io = conn.send("SET", db, table, key, payload);

        return new BonsaiFuture<>(io.handleAsync((r, e) -> null, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> deleteAsync(String key) { return null; }
    @Override
    public BonsaiFuture<Boolean> existsAsync(String key) { return null; }
    @Override
    public Query<T> find() { return null; }
}