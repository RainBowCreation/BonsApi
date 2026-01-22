package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.BonsaiRoot;
import net.rainbowcreation.bonsai.api.BonsaiTable;
import net.rainbowcreation.bonsai.api.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.api.annotation.BonsaiQuery;
import net.rainbowcreation.bonsai.api.internal.Connection;
import net.rainbowcreation.bonsai.api.util.JsonUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RemoteRoot implements BonsaiRoot {
    private final Connection connection;
    private final String db;

    public RemoteRoot(Connection connection, String db, String secret) {
        this.connection = connection;
        this.db = db;
    }

    @Override
    public <T> BonsaiTable<T> use(Class<T> type) {
        scanAndRegisterSchema(type);
        return new RemoteTable<>(connection, db, type.getSimpleName(), type);
    }

    @Override
    public BonsaiTable<Object> use(String namespace) {
        return new RemoteTable<>(connection, db, namespace, Object.class);
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
            // SEND CONFIG PACKET (Fire and Forget)
            // We tell Sidecar: "Hey, for table 'Hero', ensure these columns exist."
            // Payload: {"table": "Hero", "indices": ["name", "level"]}
            Map<String, Object> payload = new HashMap<>();
            payload.put("table", type.getSimpleName());
            payload.put("indices", indices);

            connection.send("REGISTER_SCHEMA", db, null, null, JsonUtil.toJson(payload));
        }
    }
}