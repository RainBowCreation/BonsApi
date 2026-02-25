package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.BonsaiFuture;
import net.rainbowcreation.bonsai.BonsaiTable;
import net.rainbowcreation.bonsai.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.api.config.Config;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.query.Query;
import net.rainbowcreation.bonsai.util.AUnsafe;
import net.rainbowcreation.bonsai.util.ForyFactory;
import net.rainbowcreation.bonsai.util.JsonUtil;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.apache.fory.ThreadSafeFory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RemoteTable<T> extends AUnsafe implements BonsaiTable<T> {
    private final Connection conn;
    private final String db, table;
    public final short dbId, tableId;  // Compact IDs for wire protocol
    private final Class<T> type;
    private final boolean safe;  // If true, wait for WAL and broadcast. If false, fire-and-forget.

    private static final ThreadSafeFory FORY = ForyFactory.get();
    private static final Map<Class<?>, List<Field>> fieldCache = new ConcurrentHashMap<>();

    private static final byte MAGIC_BYTE = (byte) 0xBF;  // Bonsai Fast encoding marker

    private static final byte TYPE_STRING = 0x01;    // Direct UTF-8 encoding
    private static final byte TYPE_INTEGER = 0x02;   // 4-byte big-endian int32
    private static final byte TYPE_LONG = 0x03;      // 8-byte big-endian int64
    private static final byte TYPE_BOOLEAN = 0x04;   // 1-byte: 0x00 or 0x01

    private final Cache<String, T> cache;

    public RemoteTable(Connection conn, String db, String table, Class<T> type) {
        this(conn, (short) 0, (short) 0, db, table, type, true);  // Default: safe mode, no IDs
    }

    public RemoteTable(Connection conn, String db, String table, Class<T> type, boolean safe) {
        this(conn, (short) 0, (short) 0, db, table, type, safe);
    }

    public RemoteTable(Connection conn, short dbId, short tableId, String db, String table, Class<T> type, boolean safe) {
        this.conn = conn;
        this.dbId = dbId;
        this.tableId = tableId;
        this.db = db;
        this.table = table;
        this.type = type;
        this.safe = safe;

        if (Config.CACHE_ENABLED) {
            BonsApi.LOGGER.info("LocalCache enabled for table: " + table + " (ID: " + tableId + ")");
            Caffeine<Object, Object> builder = Caffeine.newBuilder()
                    .maximumSize(Config.CACHE_MAX_SIZE)
                    .expireAfterWrite(Config.CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            if (Config.CACHE_STATS_ENABLED) {
                builder.recordStats();
            }

            this.cache = builder.build();
        }
        else {
            this.cache = null;
        }
    }

    private static final ThreadLocal<ByteBuffer> ENCODE_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocate(8192));

    private byte[] encodePrimitive(Object obj) {
        if (obj == null) return null;

        if (obj instanceof String) {
            String str = (String) obj;
            byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
            int len = strBytes.length;

            ByteBuffer buf = ENCODE_BUFFER.get();
            buf.clear();

            buf.put(MAGIC_BYTE);
            if (len < 128) {
                buf.put(TYPE_STRING);
                buf.put((byte) len);
            } else {
                buf.put(TYPE_STRING);
                buf.putInt(len | 0x80000000); // High bit set = 4-byte length
            }
            buf.put(strBytes);

            byte[] result = new byte[buf.position()];
            buf.flip();
            buf.get(result);
            return result;
        }
        else if (obj instanceof Integer) {
            int val = (Integer) obj;
            return new byte[] {
                MAGIC_BYTE,
                TYPE_INTEGER,
                (byte) (val >>> 24),
                (byte) (val >>> 16),
                (byte) (val >>> 8),
                (byte) val
            };
        }
        else if (obj instanceof Long) {
            long val = (Long) obj;
            return new byte[] {
                MAGIC_BYTE,
                TYPE_LONG,
                (byte) (val >>> 56),
                (byte) (val >>> 48),
                (byte) (val >>> 40),
                (byte) (val >>> 32),
                (byte) (val >>> 24),
                (byte) (val >>> 16),
                (byte) (val >>> 8),
                (byte) val
            };
        }
        else if (obj instanceof Boolean) {
            return new byte[] { MAGIC_BYTE, TYPE_BOOLEAN, (byte) ((Boolean) obj ? 0x01 : 0x00) };
        }

        return null; // Not a supported primitive type
    }

    private Object decodePrimitive(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return null;

        if (bytes[0] != MAGIC_BYTE) {
            return null; // Not our encoding, use Fory
        }

        byte typeMarker = bytes[1];

        switch (typeMarker) {
            case TYPE_STRING:
                
                int strLen;
                int offset;
                if (bytes.length > 2 && bytes[2] >= 0) {
                    // 1-byte length
                    strLen = bytes[2] & 0xFF;
                    offset = 3;
                } else if (bytes.length > 6) {
                    // 4-byte length with high bit set
                    strLen = ((bytes[2] & 0x7F) << 24) | ((bytes[3] & 0xFF) << 16) |
                             ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
                    offset = 6;
                } else {
                    return null; // Invalid format
                }
                return new String(bytes, offset, strLen, StandardCharsets.UTF_8);

            case TYPE_INTEGER:
                if (bytes.length < 6) return null;
                return ((bytes[2] & 0xFF) << 24) | ((bytes[3] & 0xFF) << 16) |
                       ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);

            case TYPE_LONG:
                if (bytes.length < 10) return null;
                return ((long)(bytes[2] & 0xFF) << 56) | ((long)(bytes[3] & 0xFF) << 48) |
                       ((long)(bytes[4] & 0xFF) << 40) | ((long)(bytes[5] & 0xFF) << 32) |
                       ((long)(bytes[6] & 0xFF) << 24) | ((long)(bytes[7] & 0xFF) << 16) |
                       ((long)(bytes[8] & 0xFF) << 8) | (long)(bytes[9] & 0xFF);

            case TYPE_BOOLEAN:
                if (bytes.length < 3) return null;
                return bytes[2] != 0x00;

            default:
                
                return null;
        }
    }

    @Override
    public BonsaiFuture<T> getAsync(String key) {
        T cached = getIfPresent(key);
        if (cached != null) {
            return BonsaiFuture.completed(cached);
        }

        
        CompletableFuture<byte[]> io = conn.send(RequestOp.GET, dbId, tableId, key, null, (byte) 0x01);
        CompletableFuture<T> safe = io.handleAsync((bytes, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            if (bytes == null || bytes.length == 0) return null;

            Object obj = decodePrimitive(bytes);
            if (obj == null) {
                
                if (type == Object.class) {
                    obj = deserializeWithTypeInfo(bytes);
                } else {
                    obj = FORY.deserialize(bytes);
                }
            }

            if (type == Object.class || type.isInstance(obj)) {
                @SuppressWarnings("unchecked")
                T result = (T) obj;
                put(key, result);
                return result;
            }

            
            T out = convertFromSerializable(obj, type);
            if (out == null && obj != null) {
                throw new ClassCastException(
                    "Type mismatch: table '" + table + "' expects " + type.getSimpleName() +
                    ", but stored value is " + obj.getClass().getSimpleName() +
                    ". Use .use(\"" + table + "\", Object.class) to allow mixed types."
                );
            }
            put(key, out);
            return out;
        }, BonsApi.WORKER_POOL);
        return new BonsaiFuture<>(safe);
    }

    @Override
    public BonsaiFuture<Map<String, T>> getAsync(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return BonsaiFuture.completed(Collections.emptyMap());
        }

        // Check local cache for each key, collect misses
        Map<String, T> cachedResults = new HashMap<>();
        List<String> missingKeys = new ArrayList<>();
        for (String k : keys) {
            T cached = getIfPresent(k);
            if (cached != null) {
                cachedResults.put(k, cached);
            } else {
                missingKeys.add(k);
            }
        }

        // All cached
        if (missingKeys.isEmpty()) {
            return BonsaiFuture.completed(cachedResults);
        }

        // Encode MGET payload: count(4) + [keyLen(2) + keyBytes]...
        int payloadSize = 4;
        byte[][] keyBytes = new byte[missingKeys.size()][];
        for (int i = 0; i < missingKeys.size(); i++) {
            keyBytes[i] = missingKeys.get(i).getBytes(StandardCharsets.UTF_8);
            payloadSize += 2 + keyBytes[i].length;
        }
        ByteBuffer payloadBuf = ByteBuffer.allocate(payloadSize);
        payloadBuf.putInt(missingKeys.size());
        for (byte[] kb : keyBytes) {
            payloadBuf.putShort((short) kb.length);
            payloadBuf.put(kb);
        }

        CompletableFuture<byte[]> io = conn.send(RequestOp.MGET, dbId, tableId, "", payloadBuf.array(), (byte) 0x01);
        CompletableFuture<Map<String, T>> result = io.handleAsync((body, ex) -> {
            if (ex != null) throw new RuntimeException(ex);
            if (body == null || body.length < 4) return cachedResults;

            // Decode response: count(4) + [keyLen(2) + keyBytes + valueLen(4) + valueBytes]...
            ByteBuffer buf = ByteBuffer.wrap(body);
            int count = buf.getInt();
            for (int i = 0; i < count; i++) {
                short kl = buf.getShort();
                byte[] kb = new byte[kl];
                buf.get(kb);
                String key = new String(kb, StandardCharsets.UTF_8);
                int vl = buf.getInt();
                if (vl > 0) {
                    byte[] valBytes = new byte[vl];
                    buf.get(valBytes);

                    Object obj = decodePrimitive(valBytes);
                    if (obj == null) {
                        if (type == Object.class) {
                            obj = deserializeWithTypeInfo(valBytes);
                        } else {
                            obj = FORY.deserialize(valBytes);
                        }
                    }

                    T val = null;
                    if (type == Object.class || type.isInstance(obj)) {
                        @SuppressWarnings("unchecked")
                        T casted = (T) obj;
                        val = casted;
                    } else {
                        val = convertFromSerializable(obj, type);
                    }

                    if (val != null) {
                        put(key, val);
                        cachedResults.put(key, val);
                    }
                }
            }
            return cachedResults;
        }, BonsApi.WORKER_POOL);
        return new BonsaiFuture<>(result);
    }

    @Override
    public BonsaiTable<T> withBatch(long delayMs) {
        return new BatchedTable<>(this, delayMs);
    }

    @Override
    public BonsaiFuture<Void> setAsync(String key, T value) {
        if (type != Object.class && value != null && !type.isInstance(value)) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ClassCastException(
                "Type mismatch: table '" + table + "' expects " + type.getSimpleName() +
                ", but got " + value.getClass().getSimpleName() +
                ". Use .use(\"" + table + "\", Object.class) to allow mixed types."
            ));
            return new BonsaiFuture<>(failed);
        }

        put(key, value);

        byte[] payload;

        if (value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Boolean) {
            payload = encodePrimitive(value);
        } else {
            
            if (type == Object.class) {
                payload = serializeWithTypeInfo(value);
            } else {
                
                Object toSend = convertToSerializable(value);
                payload = encodePrimitive(toSend);
                if (payload == null) {
                    payload = FORY.serialize(toSend);
                }
            }
        }

        byte flags = (byte) (safe ? 0x01 : 0x00);  // Bit 0: safe mode

        CompletableFuture<byte[]> io = conn.send(RequestOp.SET, dbId, tableId, key, payload, flags);
        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) {
                invalidate(key);
                throw new RuntimeException(e);
            }
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> setAsync(String key, T value, long ttl, TimeUnit unit) {
        if (type != Object.class && value != null && !type.isInstance(value)) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ClassCastException(
                "Type mismatch: table '" + table + "' expects " + type.getSimpleName() +
                ", but got " + value.getClass().getSimpleName() +
                ". Use .use(\"" + table + "\", Object.class) to allow mixed types."
            ));
            return new BonsaiFuture<>(failed);
        }

        put(key, value);

        byte[] data;

        if (value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Boolean) {
            data = encodePrimitive(value);
        } else {
            if (type == Object.class) {
                data = serializeWithTypeInfo(value);
            } else {
                Object toSend = convertToSerializable(value);
                data = encodePrimitive(toSend);
                if (data == null) {
                    data = FORY.serialize(toSend);
                }
            }
        }

        long expiry = System.currentTimeMillis() + unit.toMillis(ttl);
        byte[] payload = new byte[8 + data.length];
        ByteBuffer.wrap(payload).putLong(expiry);
        System.arraycopy(data, 0, payload, 8, data.length);

        byte flags = (byte) ((safe ? 0x01 : 0x00) | 0x02);

        CompletableFuture<byte[]> io = conn.send(RequestOp.SET, dbId, tableId, key, payload, flags);
        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) {
                invalidate(key);
                throw new RuntimeException(e);
            }
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Void> deleteAsync(String key) {
        invalidate(key);

        byte flags = (byte) (safe ? 0x01 : 0x00);  // Bit 0: safe mode

        CompletableFuture<byte[]> io = conn.send(RequestOp.DELETE, dbId, tableId, key, null, flags);

        return new BonsaiFuture<>(io.handleAsync((r, e) -> {
            if (e != null) throw new RuntimeException(e);
            return null;
        }, BonsApi.WORKER_POOL));
    }

    @Override
    public BonsaiFuture<Boolean> existsAsync(String key) {
        
        CompletableFuture<byte[]> io = conn.send(RequestOp.EXISTS, dbId, tableId, key, null, (byte) 0x01);
        return new BonsaiFuture<>(io.thenApplyAsync(bytes -> bytes != null && bytes.length > 0 && bytes[0] == 1, BonsApi.WORKER_POOL));
    }

    @Override
    public Query<T> find() {
        return new RemoteQuery<>(conn, dbId, tableId, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public BonsaiFuture<Map<String, Object>> statusAsync() {
        CompletableFuture<byte[]> io = conn.send(RequestOp.STATUS, dbId, tableId, "", null, (byte) 0x01);
        return new BonsaiFuture<>(io.thenApplyAsync(bytes -> {
            if (bytes == null || bytes.length == 0) return Collections.emptyMap();
            return (Map<String, Object>) FORY.deserialize(bytes);
        }, BonsApi.WORKER_POOL));
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
                BonsApi.LOGGER.warning("mapToPojo Failed for " + clazz.getSimpleName());
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

    private byte[] serializeWithTypeInfo(Object value) {
        if (value == null) return null;

        String className = value.getClass().getName();
        byte[] classNameBytes = className.getBytes(StandardCharsets.UTF_8);

        Object toSerialize = convertToSerializable(value);
        byte[] objectBytes = FORY.serialize(toSerialize);

        ByteBuffer buf = ByteBuffer.allocate(2 + classNameBytes.length + objectBytes.length);
        buf.putShort((short) classNameBytes.length);
        buf.put(classNameBytes);
        buf.put(objectBytes);

        return buf.array();
    }

    @SuppressWarnings("unchecked")
    private Object deserializeWithTypeInfo(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return null;

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        short classNameLength = buf.getShort();

        if (bytes.length < 2 + classNameLength) return null;

        byte[] classNameBytes = new byte[classNameLength];
        buf.get(classNameBytes);
        String className = new String(classNameBytes, StandardCharsets.UTF_8);

        byte[] objectBytes = new byte[bytes.length - 2 - classNameLength];
        buf.get(objectBytes);

        try {
            Class<?> clazz = Class.forName(className);
            Object mapData = FORY.deserialize(objectBytes);

            if (clazz.isInstance(mapData) || isPrimitiveOrBasic(clazz)) {
                return mapData;
            }

            if (mapData instanceof Map) {
                return mapToPojo((Map<String, Object>) mapData, clazz);
            }

            return mapData;
        } catch (ClassNotFoundException e) {
            
            return FORY.deserialize(bytes);
        }
    }

    private T getIfPresent(String key) {
        if (Config.CACHE_ENABLED) {
            if (key == null) return null;
            return cache.getIfPresent(key);
        }
        return null;
    }

    private void put(String key, T val) {
        if (Config.CACHE_ENABLED) {
            if (val == null) return;
            cache.put(key, val);
        }
    }

    public void invalidate(String key) {
        if (Config.CACHE_ENABLED) {
            cache.invalidate(key);
        }
    }

    public void invalidateAll() {
        if (Config.CACHE_ENABLED) cache.invalidateAll();
    }

    public void getStats() {
        if (Config.CACHE_ENABLED && Config.CACHE_STATS_ENABLED) cache.stats();
    }

    public String getTableName() {
        return table;
    }

    public long getCacheSize() {
        if (Config.CACHE_ENABLED) return cache.estimatedSize();
        return -1;
    }
}