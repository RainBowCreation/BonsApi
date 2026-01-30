package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.BonsaiFuture;
import net.rainbowcreation.bonsai.api.BonsaiTable;
import net.rainbowcreation.bonsai.api.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.api.connection.RequestOp;
import net.rainbowcreation.bonsai.api.query.Query;
import net.rainbowcreation.bonsai.api.util.AUnsafe;
import net.rainbowcreation.bonsai.api.util.ForyFactory;
import net.rainbowcreation.bonsai.api.util.JsonUtil;

import org.apache.fory.ThreadSafeFory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteTable<T> extends AUnsafe implements BonsaiTable<T> {
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
    }

    @Override
    public BonsaiFuture<T> getAsync(String key) {
        CompletableFuture<byte[]> io = conn.send(RequestOp.GET, db, table, key, null);
        CompletableFuture<T> safe = io.handleAsync((bytes, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            if (bytes == null || bytes.length == 0) return null;
            Object obj = FORY.deserialize(bytes);
            return convertFromSerializable(obj, type);
        }, BonsApi.WORKER_POOL);
        return new BonsaiFuture<>(safe);
    }

    @Override
    public BonsaiFuture<Void> setAsync(String key, T value) {
        Object toSend = convertToSerializable(value);
        byte[] payload = FORY.serialize(toSend);
        CompletableFuture<byte[]> io = conn.send(RequestOp.SET, db, table, key, payload);
        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) throw new RuntimeException(e);
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> deleteAsync(String key) {
        CompletableFuture<byte[]> io = conn.send(RequestOp.DELETE, db, table, key, null);
        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) throw new RuntimeException(e);
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Boolean> existsAsync(String key) {
        return new BonsaiFuture<>(conn.send(RequestOp.EXISTS, db, table, key, null)
                .thenApplyAsync(bytes -> bytes != null && bytes.length > 0 && bytes[0] == 1, BonsApi.WORKER_POOL));
    }

    @Override
    public Query<T> find() {
        return new RemoteQuery<>(conn, db, table, type);
    }

    private List<Field> getCachedFields(Class<?> clazz) {
        return fieldCache.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            Class<?> current = c;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
                    if (f.isAnnotationPresent(BonsaiIgnore.class)) continue;
                    f.setAccessible(true);
                    list.add(f);
                }
                current = current.getSuperclass();
            }
            return list;
        });
    }

    private Object convertToSerializable(Object val) {
        if (val == null) return null;
        if (isPrimitiveOrBasic(val.getClass())) return val;
        if (val instanceof Enum) return ((Enum<?>) val).name();

        if (val instanceof Map) {
            Map<String, Object> copy = new HashMap<>();
            ((Map<?, ?>) val).forEach((k, v) -> copy.put(String.valueOf(k), convertToSerializable(v)));
            return copy;
        }
        if (val instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object o : (List<?>) val) copy.add(convertToSerializable(o));
            return copy;
        }
        if (val.getClass().isArray()) {
            int len = Array.getLength(val);
            List<Object> copy = new ArrayList<>(len);
            for (int i = 0; i < len; i++) copy.add(convertToSerializable(Array.get(val, i)));
            return copy;
        }

        // POJO -> Map
        Map<String, Object> map = new HashMap<>();
        try {
            for (Field f : getCachedFields(val.getClass())) {
                Object v = f.get(val);
                if (v != null) map.put(f.getName(), convertToSerializable(v));
            }
        } catch (Exception ignored) {}
        return map;
    }

    @SuppressWarnings("unchecked")
    private T convertFromSerializable(Object obj, Class<T> clazz) {
        if (obj == null) return null;
        if (clazz.isInstance(obj)) return clazz.cast(obj);

        if (obj instanceof Map) {
            return mapToPojo((Map<String, Object>) obj, clazz);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <R> R mapToPojo(Map<String, Object> map, Class<R> clazz) {
        try {
            R instance = (R) unsafe.allocateInstance(clazz);

            for (Field f : getCachedFields(clazz)) {
                Object val = map.get(f.getName());
                if (val != null) {
                    f.set(instance, coerce(val, f.getType()));
                }
            }
            return instance;
        } catch (Exception e) {
            try {
                String json = JsonUtil.toJson(map);
                return JsonUtil.fromJson(json, clazz);
            } catch (Exception ex) {
                System.err.println("[Bonsai] mapToPojo Failed for " + clazz.getSimpleName());
                ex.printStackTrace();
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object coerce(Object val, Class<?> target) {
        if (val == null) return null;
        if (target.isInstance(val)) return val;

        if (target.isEnum() && val instanceof String) {
            return Enum.valueOf((Class<Enum>) target, (String) val);
        }
        if (target == Long.class || target == long.class) return ((Number) val).longValue();
        if (target == Integer.class || target == int.class) return ((Number) val).intValue();
        if (target == Double.class || target == double.class) return ((Number) val).doubleValue();
        if (target == Float.class || target == float.class) return ((Number) val).floatValue();
        if (target == Boolean.class || target == boolean.class) {
            if (val instanceof Number) return ((Number) val).intValue() != 0;
            return Boolean.valueOf(val.toString());
        }

        if (val instanceof Map && !Map.class.isAssignableFrom(target)) {
            return mapToPojo((Map<String, Object>) val, target);
        }
        return val;
    }

    private boolean isPrimitiveOrBasic(Class<?> c) {
        return c.isPrimitive() || Number.class.isAssignableFrom(c) ||
                Boolean.class == c || String.class == c || Character.class == c;
    }
}