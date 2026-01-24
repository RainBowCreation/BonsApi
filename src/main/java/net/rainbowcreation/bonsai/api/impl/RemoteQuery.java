package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.BonsaiFuture;
import net.rainbowcreation.bonsai.api.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.api.query.*;
import net.rainbowcreation.bonsai.api.util.CastUtil;
import net.rainbowcreation.bonsai.api.util.ForyFactory;
import net.rainbowcreation.bonsai.api.util.JsonUtil;
import org.apache.fory.ThreadSafeFory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteQuery<T> implements Query<T> {

    private final Connection conn;
    private final String db;
    private final String table;
    private final Class<T> type;
    private static final ThreadSafeFory FORY = ForyFactory.get();

    private final SearchCriteria rootCriteria = new SearchCriteria();
    private int limit = -1;
    private int offset = -1;
    private final Map<String, Integer> sorts = new HashMap<>();

    // Performance Cache for Reflection
    private static final Map<Class<?>, List<Field>> fieldCache = new ConcurrentHashMap<>();

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

            Object raw = FORY.deserialize(bytes);
            if (!(raw instanceof List)) return new ArrayList<>();
            List<?> rawList = (List<?>) raw;

            List<T> result = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                if (item == null) continue;
                if (type.isInstance(item)) {
                    result.add(type.cast(item));
                }
                else if (item instanceof Map) {
                    result.add(mapToPojo((Map<?, ?>) item, type));
                }
            }
            return result;

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

    private T mapToPojo(Map<?, ?> map, Class<T> clazz) {
        try {
            T instance = clazz.getConstructor().newInstance();
            for (Field f : getCachedFields(clazz)) {
                Object val = map.get(f.getName());
                if (val != null) {
                    f.set(instance, CastUtil.coerce(val, f.getType()));
                }
            }
            return instance;
        } catch (Exception e) {
            String json = JsonUtil.toJson(map);
            return JsonUtil.fromJson(json, clazz);
        }
    }

    private List<Field> getCachedFields(Class<?> clazz) {
        return fieldCache.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
                if (f.isAnnotationPresent(BonsaiIgnore.class)) continue;
                f.setAccessible(true);
                list.add(f);
            }
            return list;
        });
    }
}