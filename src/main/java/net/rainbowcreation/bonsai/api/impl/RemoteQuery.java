package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.BonsaiFuture;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.api.query.*;
import net.rainbowcreation.bonsai.api.util.JsonUtil;
import org.apache.fory.ThreadSafeFory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RemoteQuery<T> implements Query<T> {

    private final Connection conn;
    private final String db;
    private final String table;
    private final Class<T> type;
    private final ThreadSafeFory fory;

    private final SearchCriteria rootCriteria = new SearchCriteria();
    private int limit = -1;
    private int offset = -1;
    private final Map<String, Integer> sorts = new HashMap<>();

    public RemoteQuery(Connection conn, String db, String table, Class<T> type, ThreadSafeFory fory) {
        this.conn = conn;
        this.db = db;
        this.table = table;
        this.type = type;
        this.fory = fory;
    }

    @Override
    public Query<T> where(String field, QueryOp op, Object val) {
        rootCriteria.and(field, op, val);
        return this;
    }

    @Override
    public Query<T> where(String rawQueryString) {
        rootCriteria.and(rawQueryString, QueryOp.EQ, null);
        return this;
    }

    @Override
    public Query<T> filter(SearchCriteria criteria) {
        rootCriteria.and(criteria);
        return this;
    }

    @Override
    public Query<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    @Override
    public Query<T> offset(int offset) {
        this.offset = offset;
        return this;
    }

    @Override
    public Query<T> sort(String field, SortOrder order) {
        this.sorts.put(field, order == SortOrder.ASC ? 1 : -1);
        return this;
    }

    @Override
    public BonsaiFuture<List<T>> getAsync() {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("filter", rootCriteria.buildRoot());
        if (limit > 0) payloadMap.put("limit", limit);
        if (offset >= 0) payloadMap.put("offset", offset);
        if (!sorts.isEmpty()) payloadMap.put("sort", sorts);

        byte[] reqBytes = JsonUtil.toJson(payloadMap).getBytes(StandardCharsets.UTF_8);

        CompletableFuture<byte[]> io = conn.send("QUERY_GET", db, table, null, reqBytes);

        return new BonsaiFuture<>(io.handleAsync((bytes, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            if (bytes == null || bytes.length == 0) return new ArrayList<>();
            return (List<T>) fory.deserialize(bytes);

        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Integer> countAsync() {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("filter", rootCriteria.buildRoot());

        byte[] reqBytes = JsonUtil.toJson(payloadMap).getBytes(StandardCharsets.UTF_8);

        CompletableFuture<byte[]> io = conn.send("QUERY_COUNT", db, table, null, reqBytes);

        return new BonsaiFuture<>(io.handleAsync((bytes, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            String numStr = new String(bytes, StandardCharsets.UTF_8);
            return Integer.parseInt(numStr);
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> setAsync(Map<String, Object> updates) {
        UpdatePayload payloadObj = new UpdatePayload(rootCriteria.buildRoot(), updates);
        byte[] reqBytes = JsonUtil.toJson(payloadObj).getBytes(StandardCharsets.UTF_8);

        CompletableFuture<byte[]> io = conn.send("QUERY_UPDATE", db, table, null, reqBytes);

        return new BonsaiFuture<>(io.handleAsync((res, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> setAsync(String field, Object value) {
        return setAsync(Collections.singletonMap(field, value));
    }

    @Override
    public BonsaiFuture<Void> deleteAsync() {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("filter", rootCriteria.buildRoot());

        byte[] reqBytes = JsonUtil.toJson(payloadMap).getBytes(StandardCharsets.UTF_8);
        CompletableFuture<byte[]> io = conn.send("QUERY_DELETE", db, table, null, reqBytes);

        return new BonsaiFuture<>(io.handleAsync((res, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            return null;
        }, BonsApi.WORKER_POOL));
    }
}