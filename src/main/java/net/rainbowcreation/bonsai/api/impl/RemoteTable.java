package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.BonsaiFuture;
import net.rainbowcreation.bonsai.api.BonsaiTable;
import net.rainbowcreation.bonsai.api.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.api.query.Query;
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

public class RemoteTable<T> implements BonsaiTable<T> {
    private final Connection conn;
    private final String db, table;
    private final Class<T> type;

    private static final ThreadSafeFory FORY = ForyFactory.get();

    private static final Map<Class<?>, List<Field>> fieldCache = new ConcurrentHashMap<>();

    public RemoteTable(Connection conn, String db, String table, Class<T> type) {
        this.conn = conn;
        this.db = db;
        this.table = table;
        this.type = type;

        registerSchema();
    }

    private void registerSchema() {
        if (type == Object.class || type == Map.class) return;

        List<String> indices = new ArrayList<>();
        for (Field f : getCachedFields(type)) {
            indices.add(f.getName());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("table", table);
        payload.put("indices", indices);

        // Send schema registration (Fire and wait)
        byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        try {
            conn.send("REGISTER_SCHEMA", db, table, null, bytes).join();
        } catch (Exception e) {
            System.err.println("[Bonsai] Warning: Schema registration failed: " + e.getMessage());
        }
    }

    @Override
    public BonsaiFuture<T> getAsync(String key) {
        CompletableFuture<byte[]> io = conn.send("GET", db, table, key, null);

        CompletableFuture<T> safe = io.handleAsync((bytes, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            if (bytes == null || bytes.length == 0) return null;

            Object obj = FORY.deserialize(bytes);

            if (type.isInstance(obj)) return type.cast(obj);

            if (obj instanceof Map) {
                return mapToPojo((Map<?, ?>) obj, type);
            }

            return null;
        }, BonsApi.WORKER_POOL);

        return new BonsaiFuture<>(safe);
    }

    @Override
    public BonsaiFuture<Void> setAsync(String key, T value) {
        Object toSend = value;

        // OPTIMIZATION: Convert POJO -> Map directly (No JSON String intermediate)
        if (type != Object.class && type != Map.class && !(value instanceof Map)) {
            toSend = pojoToMap(value);
        }

        byte[] payload = FORY.serialize(toSend);
        CompletableFuture<byte[]> io = conn.send("SET", db, table, key, payload);

        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) throw new RuntimeException(e);
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> deleteAsync(String key) {
        CompletableFuture<byte[]> io = conn.send("DELETE", db, table, key, null);
        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) throw new RuntimeException(e);
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Boolean> existsAsync(String key) {
        return new BonsaiFuture<>(conn.send("EXISTS", db, table, key, null)
                .thenApplyAsync(bytes -> bytes != null && bytes.length > 0 && bytes[0] == 1, BonsApi.WORKER_POOL));
    }

    @Override
    public Query<T> find() {
        return new RemoteQuery<>(conn, db, table, type);
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

    private Map<String, Object> pojoToMap(T pojo) {
        Map<String, Object> map = new HashMap<>();
        try {
            for (Field f : getCachedFields(type)) {
                Object val = f.get(pojo);
                if (val != null) map.put(f.getName(), val);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Pojo Mapping Error", e);
        }
        return map;
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
            try {
                String json = JsonUtil.toJson(map);
                return JsonUtil.fromJson(json, clazz);
            } catch (Exception ex) {
                throw new RuntimeException("Deserialization Failed for " + clazz.getSimpleName(), e);
            }
        }
    }
}