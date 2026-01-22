package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.BonsaiFuture;
import net.rainbowcreation.bonsai.api.internal.Connection;
import net.rainbowcreation.bonsai.api.query.*;
import net.rainbowcreation.bonsai.api.util.JsonUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RemoteQuery<T> implements Query<T> {

    private final Connection conn;
    private final String db;
    private final String table;
    private final Class<T> type;

    private SearchCriteria rootCriteria = new SearchCriteria();

    private int limit = -1;
    private int offset = -1;
    private final Map<String, Integer> sorts = new HashMap<>();

    public RemoteQuery(Connection conn, String db, String table, Class<T> type) {
        this.conn = conn;
        this.db = db;
        this.table = table;
        this.type = type;
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
        Map<String, Object> payload = new HashMap<>();
        payload.put("filter", rootCriteria.buildRoot());
        if (limit > 0) payload.put("limit", limit);
        if (offset >= 0) payload.put("offset", offset);
        if (!sorts.isEmpty()) payload.put("sort", sorts);

        CompletableFuture<String> io = conn.send("QUERY_GET", db, table, null, JsonUtil.toJson(payload));

        return new BonsaiFuture<>(io.handleAsync((json, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            return JsonUtil.fromJsonList(json, type);
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Integer> countAsync() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("filter", rootCriteria.buildRoot());

        CompletableFuture<String> io = conn.send("QUERY_COUNT", db, table, null, JsonUtil.toJson(payload));

        return new BonsaiFuture<>(io.handleAsync((json, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            return Integer.parseInt(json);
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> setAsync(Map<String, Object> updates) {
        UpdatePayload payload = new UpdatePayload(rootCriteria.buildRoot(), updates);

        CompletableFuture<String> io = conn.send("QUERY_UPDATE", db, table, null, JsonUtil.toJson(payload));

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
        Map<String, Object> payload = new HashMap<>();
        payload.put("filter", rootCriteria.buildRoot());

        CompletableFuture<String> io = conn.send("QUERY_DELETE", db, table, null, JsonUtil.toJson(payload));

        return new BonsaiFuture<>(io.handleAsync((res, ex) -> null, BonsApi.WORKER_POOL));
    }
}