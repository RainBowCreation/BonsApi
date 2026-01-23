package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsaiRoot;
import net.rainbowcreation.bonsai.api.BonsaiTable;
import net.rainbowcreation.bonsai.api.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.api.annotation.BonsaiQuery;
import net.rainbowcreation.bonsai.api.connection.Connection;
import net.rainbowcreation.bonsai.api.util.ForyFactory;
import net.rainbowcreation.bonsai.api.util.JsonUtil;

import org.apache.fory.ThreadSafeFory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.nio.charset.StandardCharsets;

public class RemoteRoot implements BonsaiRoot {
    private final Connection connection;
    private final String db;
    private final ThreadSafeFory fory = ForyFactory.get();

    public RemoteRoot(Connection connection, String db, String secret) {
        this.connection = connection;
        this.db = db;
    }

    @Override
    public <T> BonsaiTable<T> use(Class<T> type) {
        scanAndRegisterSchema(type);
        return new RemoteTable<>(connection, db, type.getSimpleName(), type, fory);
    }

    @Override
    public BonsaiTable<Object> use(String namespace) {
        return new RemoteTable<>(connection, db, namespace, Object.class, fory);
    }

    private void scanAndRegisterSchema(Class<?> type) {
        List<String> indices = new ArrayList<>();
        boolean indexAll = type.isAnnotationPresent(BonsaiQuery.class);

        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(BonsaiIgnore.class) ||
                    Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            if (indexAll || field.isAnnotationPresent(BonsaiQuery.class)) {
                indices.add(field.getName());
            }
        }

        if (!indices.isEmpty()) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("table", type.getSimpleName());
            payload.put("indices", indices);

            byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);

            connection.send("REGISTER_SCHEMA", db, null, null, bytes);
        }
    }
}