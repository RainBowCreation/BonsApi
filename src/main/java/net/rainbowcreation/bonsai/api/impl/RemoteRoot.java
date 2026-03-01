package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.BonsaiRoot;
import net.rainbowcreation.bonsai.BonsaiTable;
import net.rainbowcreation.bonsai.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.annotation.BonsaiQuery;
import net.rainbowcreation.bonsai.api.BonsApi;
import net.rainbowcreation.bonsai.api.config.Config;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.util.JsonUtil;
import net.rainbowcreation.bonsai.registry.IdRegistry;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import java.nio.ByteBuffer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import java.nio.charset.StandardCharsets;

public class RemoteRoot implements BonsaiRoot {
    private final Connection connection;
    private final String db;

    private final Map<String, RemoteTable<?>> cachedTables = new ConcurrentHashMap<>();
    private volatile boolean invalidationCallbackRegistered = false;

    
    private final IdRegistry idRegistry = new IdRegistry();
    private volatile Short cachedDbId = null;

    public RemoteRoot(Connection connection, String db, String secret) {
        this.connection = connection;
        this.db = db;
    }

    @Override
    public <T> BonsaiTable<T> use(Class<T> type) {
        return use(type, true);  // Default: safe mode
    }

    @Override
    public <T> BonsaiTable<T> use(Class<T> type, boolean safe) {
        short tableId = scanAndRegisterSchema(type);
        short dbId = getOrRegisterDatabaseId();

        RemoteTable<T> table = new RemoteTable<>(connection, dbId, tableId, db, type.getSimpleName(), type, safe);
        if (Config.CACHE_ENABLED) {
            return createCachedTable(table, type.getSimpleName());
        }
        return table;
    }

    @Override
    public BonsaiTable<Object> use(String namespace) {
        return use(namespace, true);
    }

    @Override
    public BonsaiTable<Object> use(String tableName, boolean safe) {
        short dbId = getOrRegisterDatabaseId();
        short tableId = getOrRegisterTableId(tableName);

        RemoteTable<Object> table = new RemoteTable<>(connection, dbId, tableId, db, tableName, Object.class, safe);
        if (Config.CACHE_ENABLED) {
            return createCachedTable(table, tableName);
        }
        return table;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> BonsaiTable<T> use(String tableName, Class<T> type, boolean safe) {
        short dbId = getOrRegisterDatabaseId();
        short tableId;

        if (type != null && type != Object.class) {
            tableId = scanAndRegisterSchema(type);
        } else {
            tableId = getOrRegisterTableId(tableName);
        }

        RemoteTable<T> table = new RemoteTable<>(connection, dbId, tableId, db, tableName, type == null ? (Class<T>) Object.class : type, safe);
        if (Config.CACHE_ENABLED) {
            return createCachedTable(table, tableName);
        }
        return table;
    }

    private short scanAndRegisterSchema(Class<?> type) {
        String tableName = type.getSimpleName();

        
        Short cached = idRegistry.getTableId(db, tableName);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> payload = getStringObjectMap(type, tableName);

        byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] response = connection.send(RequestOp.REGISTER_SCHEMA, (short) 0, (short) 0, db, bytes, (byte) 0x01).get(5, TimeUnit.SECONDS);

            
            if (response != null && response.length >= 3) {
                ByteBuffer buf = ByteBuffer.wrap(response);
                short dbId = (short) (buf.get() & 0xFF);
                short tableId = buf.getShort();


                idRegistry.registerDatabase(db, dbId);
                idRegistry.registerTable(db, tableName, tableId);

                if (cachedDbId == null) {
                    cachedDbId = dbId;
                }

                return tableId;
            } else {
                throw new RuntimeException("Invalid response from REGISTER_SCHEMA: expected 3 bytes, got " +
                    (response == null ? "null" : response.length));
            }
        } catch (Exception e) {
            throw new RuntimeException("Schema registration failed for " + tableName, e);
        }
    }

    private @NonNull Map<String, Object> getStringObjectMap(Class<?> type, String tableName) {
        List<Map<String, Object>> columns = new ArrayList<>();
        boolean classLevelQuery = type.isAnnotationPresent(BonsaiQuery.class);

        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(BonsaiIgnore.class) ||
                    Modifier.isTransient(field.getModifiers()) ||
                    Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            boolean isColumn = false;
            boolean indexed = false;
            String sqlType = "";
            int length = 255;
            boolean unique = false;

            if (classLevelQuery) {
                isColumn = true;
            }

            if (field.isAnnotationPresent(BonsaiQuery.class)) {
                BonsaiQuery ann = field.getAnnotation(BonsaiQuery.class);
                isColumn = true;
                indexed = ann.indexed();
                sqlType = ann.type();
                length = ann.length();
                unique = ann.unique();
            }

            if (isColumn) {
                Class<?> fieldType = field.getType();
                if (isSimpleType(fieldType)) {
                    Map<String, Object> colMeta = new HashMap<>();
                    colMeta.put("name", field.getName());
                    colMeta.put("indexed", indexed);
                    colMeta.put("unique", unique);
                    colMeta.put("length", length);

                    if (sqlType.isEmpty()) {
                        sqlType = inferSqlType(fieldType, length);
                    }
                    colMeta.put("type", sqlType);

                    columns.add(colMeta);
                }
            }
        }


        Map<String, Object> payload = new HashMap<>();
        payload.put("table", tableName);
        payload.put("columns", columns);
        return payload;
    }

    private short getOrRegisterDatabaseId() {
        if (cachedDbId != null) {
            return cachedDbId;
        }

        
        Map<String, Object> payload = new HashMap<>();
        payload.put("table", "");
        payload.put("columns", Collections.emptyList());

        byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] response = connection.send(RequestOp.REGISTER_SCHEMA, (short) 0, (short) 0, db, bytes, (byte) 0x01).get(5, TimeUnit.SECONDS);

            if (response != null && response.length >= 3) {
                ByteBuffer buf = ByteBuffer.wrap(response);
                short dbId = (short) (buf.get() & 0xFF);

                idRegistry.registerDatabase(db, dbId);
                cachedDbId = dbId;

                return dbId;
            } else {
                throw new RuntimeException("Invalid response from REGISTER_SCHEMA (database)");
            }
        } catch (Exception e) {
            throw new RuntimeException("Database registration failed for " + db, e);
        }
    }

    private short getOrRegisterTableId(String tableName) {
        
        Short cached = idRegistry.getTableId(db, tableName);
        if (cached != null) {
            return cached;
        }

        
        Map<String, Object> payload = new HashMap<>();
        payload.put("table", tableName);
        payload.put("columns", Collections.emptyList());

        byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] response = connection.send(RequestOp.REGISTER_SCHEMA, (short) 0, (short) 0, db, bytes, (byte) 0x01).get(5, TimeUnit.SECONDS);

            if (response != null && response.length >= 3) {
                ByteBuffer buf = ByteBuffer.wrap(response);
                short dbId = (short) (buf.get() & 0xFF);
                short tableId = buf.getShort();

                idRegistry.registerDatabase(db, dbId);
                idRegistry.registerTable(db, tableName, tableId);

                if (cachedDbId == null) {
                    cachedDbId = dbId;
                }

                return tableId;
            } else {
                throw new RuntimeException("Invalid response from REGISTER_SCHEMA (table)");
            }
        } catch (Exception e) {
            throw new RuntimeException("Table registration failed for " + tableName, e);
        }
    }

    private boolean isSimpleType(Class<?> type) {
        return type == String.class ||
               type == Integer.class || type == int.class ||
               type == Long.class || type == long.class ||
               type == Double.class || type == double.class ||
               type == Float.class || type == float.class ||
               type == Boolean.class || type == boolean.class ||
               type == Byte.class || type == byte.class ||
               type == Short.class || type == short.class;
    }

    private String inferSqlType(Class<?> type, int length) {
        if (type == String.class) return "VARCHAR(" + length + ")";
        if (type == Integer.class || type == int.class) return "INT";
        if (type == Long.class || type == long.class) return "BIGINT";
        if (type == Double.class || type == double.class) return "DOUBLE";
        if (type == Float.class || type == float.class) return "FLOAT";
        if (type == Boolean.class || type == boolean.class) return "BOOLEAN";
        if (type == Byte.class || type == byte.class) return "TINYINT";
        if (type == Short.class || type == short.class) return "SMALLINT";
        return "VARCHAR(255)";
    }

    private <T> BonsaiTable<T> createCachedTable(RemoteTable<T> remoteTable, String tableName) {
        if (!invalidationCallbackRegistered) {
            synchronized (this) {
                if (!invalidationCallbackRegistered) {
                    connection.setInvalidationCallback(this::handleInvalidation);
                    invalidationCallbackRegistered = true;
                    BonsApi.LOGGER.info("Client-side cache enabled for database: " + db);
                }
            }
        }

        cachedTables.put(tableName, remoteTable);

        try {
            connection.send(RequestOp.SUBSCRIBE, remoteTable.dbId, remoteTable.tableId, null, null, (byte) 0x01)
                    .get(5, TimeUnit.SECONDS);
            BonsApi.LOGGER.info("Subscribed to cache invalidations for table: " + tableName);
        } catch (Exception e) {
            BonsApi.LOGGER.warning("Failed to subscribe to invalidations for " + tableName + ": " + e.getMessage());
        }

        return remoteTable;
    }

    private void handleInvalidation(String dbName, String tableName, String key) {
        if (!db.equals(dbName)) {
            return;
        }

        RemoteTable<?> table = cachedTables.get(tableName);
        if (table != null) {
            if (key == null || key.isEmpty()) {
                table.invalidateAll();
            } else {
                table.invalidate(key);
            }
        }
    }
}