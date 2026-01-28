package net.rainbowcreation.bonsai.api.util;

import com.dslplatform.json.*;
import com.dslplatform.json.runtime.Settings;
import net.rainbowcreation.bonsai.api.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.api.query.*;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JsonUtil {

    private static final DslJson<Object> dsl = new DslJson<>(Settings.withRuntime().includeServiceLoader());
    private static final ThreadLocal<JsonWriter> localWriter = ThreadLocal.withInitial(dsl::newWriter);
    private static final Map<Class<?>, JsonWriter.WriteObject<Object>> pojoWriters = new HashMap<>();

    static {
        dsl.registerWriter(FilterNode.class, (writer, f) -> {
            writer.writeByte(JsonWriter.OBJECT_START);
            writer.writeAscii("\"type\":\"FILTER\",\"field\":");
            writer.writeString(f.field);
            writer.writeAscii(",\"op\":");
            writer.writeAscii(Integer.toString(f.op));
            writer.writeAscii(",\"value\":");
            serialize(writer, f.value);
            writer.writeByte(JsonWriter.OBJECT_END);
        });

        dsl.registerWriter(GroupNode.class, (writer, g) -> {
            try {
                writer.writeByte(JsonWriter.OBJECT_START);
                writer.writeAscii("\"type\":\"GROUP\",\"logic\":");
                writer.writeString(g.logic);
                writer.writeAscii(",\"children\":");
                dsl.serialize(writer, g.children);
                writer.writeByte(JsonWriter.OBJECT_END);
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        dsl.registerWriter(UpdatePayload.class, (writer, u) -> {
            try {
                writer.writeByte(JsonWriter.OBJECT_START);
                writer.writeAscii("\"where\":"); serialize(writer, u.where);
                writer.writeAscii(",\"set\":"); dsl.serialize(writer, u.set);
                writer.writeByte(JsonWriter.OBJECT_END);
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        dsl.registerReader(Criterion.class, reader -> {
            try {
                if (reader.last() != '{') throw reader.newParseError("Expected '{'");

                reader.getNextToken(); // Move to key
                String key = reader.readKey();
                if (!"type".equals(key)) throw reader.newParseError("Expected 'type' field first");

                String type = reader.readString();

                byte next = reader.getNextToken();
                if (next != ',') throw reader.newParseError("Expected ',' after type");

                reader.getNextToken(); // Move to next key start '"'

                if ("FILTER".equals(type)) {
                    return deserializeFilter(reader);
                } else if ("GROUP".equals(type)) {
                    return deserializeGroup(reader);
                }
                throw reader.newParseError("Unknown Criterion Type: " + type);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static FilterNode deserializeFilter(JsonReader<?> reader) throws IOException {
        String field = null;
        int op = 0;
        Object value = null;

        // Loop: reader.last() is '"'
        while (reader.last() == '"') {
            String key = reader.readKey(); // Advances to value start

            if ("field".equals(key)) {
                field = reader.readString();
            } else if ("op".equals(key)) {
                op = NumberConverter.deserializeInt(reader);
            } else if ("value".equals(key)) {
                value = ObjectConverter.deserializeObject(reader);
            } else {
                reader.skip();
            }

            byte last = reader.last();
            if (last == '"' || last == ']' || last == '}') {
                reader.getNextToken(); // Move to delimiter
            }

            if (reader.last() == ',') {
                reader.getNextToken(); // Move to next key '"'
            } else if (reader.last() == '}') {
                break;
            } else {
                throw reader.newParseError("Expected ',' or '}'");
            }
        }
        return new FilterNode(field, idToOp(op), value);
    }

    private static GroupNode deserializeGroup(JsonReader<?> reader) throws IOException {
        String logic = "AND";
        List<Criterion> children = null;

        while (reader.last() == '"') {
            String key = reader.readKey();

            if ("logic".equals(key)) {
                logic = reader.readString();
            } else if ("children".equals(key)) {
                children = reader.readCollection(dsl.tryFindReader(Criterion.class));
            } else {
                reader.skip();
            }

            byte last = reader.last();
            if (last == '"' || last == ']' || last == '}') {
                reader.getNextToken();
            }

            if (reader.last() == ',') {
                reader.getNextToken();
            } else if (reader.last() == '}') {
                break;
            }
        }
        return new GroupNode(logic, children);
    }

    private static QueryOp idToOp(int id) {
        for (QueryOp op : QueryOp.values()) {
            if (op.getId() == id) return op;
        }
        return QueryOp.EQ;
    }

    public static String toJson(Object instance) {
        if (instance == null) return "null";
        JsonWriter writer = localWriter.get();
        writer.reset();
        serialize(writer, instance);
        return writer.toString();
    }

    public static void serialize(JsonWriter writer, Object o) {
        if (o == null) {
            writer.writeNull();
            return;
        }
        Class<?> type = o.getClass();
        if (dsl.serialize(writer, type, o)) {
            return;
        }
        getPojoWriter(type).write(writer, o);
    }

    private static JsonWriter.WriteObject<Object> getPojoWriter(Class<?> type) {
        JsonWriter.WriteObject<Object> writer = pojoWriters.get(type);
        if (writer != null) return writer;
        return registerPojo(type);
    }

    private synchronized static JsonWriter.WriteObject<Object> registerPojo(Class<?> type) {
        if (pojoWriters.containsKey(type)) return pojoWriters.get(type);

        List<FieldSerializer> fieldSer = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        try {
            for (Field field : type.getDeclaredFields()) {
                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                if (field.isAnnotationPresent(BonsaiIgnore.class)) continue;

                field.setAccessible(true);
                MethodHandle handle = lookup.unreflectGetter(field);
                byte[] keyBytes = ("\"" + field.getName() + "\":").getBytes(StandardCharsets.UTF_8);
                fieldSer.add(new FieldSerializer(keyBytes, handle));
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Access Error", e);
        }

        JsonWriter.WriteObject<Object> fastWriter = (writer, instance) -> {
            writer.writeByte(JsonWriter.OBJECT_START);
            boolean first = true;

            for (FieldSerializer fs : fieldSer) {
                try {
                    Object val = fs.handle.invoke(instance);
                    if (val != null) {
                        if (!first) writer.writeByte(JsonWriter.COMMA);
                        writer.writeAscii(fs.key);
                        serialize(writer, val);
                        first = false;
                    }
                } catch (Throwable t) {
                    throw new RuntimeException("Field Access Error", t);
                }
            }
            writer.writeByte(JsonWriter.OBJECT_END);
        };

        pojoWriters.put(type, fastWriter);
        return fastWriter;
    }

    private static class FieldSerializer {
        final byte[] key;
        final MethodHandle handle;
        FieldSerializer(byte[] k, MethodHandle h) { key = k; handle = h; }
    }

    public static void registerType(Class<?> type) {
        if (!pojoWriters.containsKey(type)) {
            registerPojo(type);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) return null;
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return dsl.deserialize(type, bytes, bytes.length);
        } catch (IOException e) {
            throw new RuntimeException("JSON Deserialization Failed for " + type.getSimpleName(), e);
        }
    }
}