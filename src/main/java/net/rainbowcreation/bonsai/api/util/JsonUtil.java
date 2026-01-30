package net.rainbowcreation.bonsai.api.util;

import net.rainbowcreation.bonsai.api.annotation.BonsaiIgnore;
import net.rainbowcreation.bonsai.api.query.*;

import com.dslplatform.json.*;
import com.dslplatform.json.runtime.Settings;

import java.io.IOException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sun.misc.Unsafe;

public class JsonUtil {

    private static final DslJson<Object> dsl = new DslJson<>(Settings.withRuntime().includeServiceLoader());
    private static final ThreadLocal<JsonWriter> localWriter = ThreadLocal.withInitial(dsl::newWriter);
    private static final Map<Class<?>, JsonWriter.WriteObject<Object>> pojoWriters = new HashMap<>();

    private static final Unsafe unsafe;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Could not access Unsafe", e);
        }

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
                reader.getNextToken();
                String key = reader.readKey();
                if (!"type".equals(key)) throw reader.newParseError("Expected 'type' field first");
                String type = reader.readString();
                byte next = reader.getNextToken();
                if (next != ',') throw reader.newParseError("Expected ',' after type");
                reader.getNextToken();

                if ("FILTER".equals(type)) {
                    return deserializeFilter(reader);
                } else if ("GROUP".equals(type)) {
                    return deserializeGroup(reader);
                }
                throw reader.newParseError("Unknown Criterion Type: " + type);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
    }

    private static FilterNode deserializeFilter(JsonReader<?> reader) throws IOException {
        String field = null;
        int op = 0;
        Object value = null;
        while (reader.last() == '"') {
            String key = reader.readKey();
            if ("field".equals(key)) field = reader.readString();
            else if ("op".equals(key)) op = NumberConverter.deserializeInt(reader);
            else if ("value".equals(key)) value = ObjectConverter.deserializeObject(reader);
            else reader.skip();
            byte last = reader.last();
            if (last == '"' || last == ']' || last == '}') reader.getNextToken();
            if (reader.last() == ',') reader.getNextToken();
            else if (reader.last() == '}') break;
            else throw reader.newParseError("Expected ',' or '}'");
        }
        return new FilterNode(field, idToOp(op), value);
    }

    private static GroupNode deserializeGroup(JsonReader<?> reader) throws IOException {
        String logic = "AND";
        List<Criterion> children = null;
        while (reader.last() == '"') {
            String key = reader.readKey();
            if ("logic".equals(key)) logic = reader.readString();
            else if ("children".equals(key)) children = reader.readCollection(dsl.tryFindReader(Criterion.class));
            else reader.skip();
            byte last = reader.last();
            if (last == '"' || last == ']' || last == '}') reader.getNextToken();
            if (reader.last() == ',') reader.getNextToken();
            else if (reader.last() == '}') break;
        }
        return new GroupNode(logic, children);
    }

    private static QueryOp idToOp(int id) {
        for (QueryOp op : QueryOp.values()) if (op.getId() == id) return op;
        return QueryOp.EQ;
    }

    public static String toJson(Object instance) {
        if (instance == null) return "null";
        JsonWriter writer = localWriter.get();
        writer.reset();
        try {
            serialize(writer, instance);
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
        return writer.toString();
    }

    @SuppressWarnings("unchecked")
    public static void serialize(JsonWriter writer, Object o) {
        if (o == null) {
            writer.writeNull();
            return;
        }
        Class<?> type = o.getClass();

        if (dsl.serialize(writer, (Class<Object>) type, o)) return;

        if (type.isEnum()) {
            registerEnum(type);
            dsl.serialize(writer, (Class<Object>) type, o);
            return;
        }

        getPojoWriter(type).write(writer, o);
    }

    private static JsonWriter.WriteObject<Object> getPojoWriter(Class<?> type) {
        JsonWriter.WriteObject<Object> writer = pojoWriters.get(type);
        if (writer != null) return writer;
        return registerPojo(type);
    }

    @SuppressWarnings("unchecked")
    private synchronized static void registerEnum(Class<?> type) {
        if (dsl.tryFindWriter(type) != null) return;

        Class<Object> raw = (Class<Object>) type;

        dsl.registerWriter(raw, (writer, obj) -> writer.writeString(((Enum<?>)obj).name()));

        dsl.registerReader(raw, reader -> {
            try {
                String name = reader.readString();
                for (Object e : raw.getEnumConstants()) {
                    if (((Enum<?>)e).name().equals(name)) return e;
                }
                return null;
            } catch (IOException e) { throw new RuntimeException(e); }
        });
    }

    private synchronized static JsonWriter.WriteObject<Object> registerPojo(Class<?> type) {
        if (pojoWriters.containsKey(type)) return pojoWriters.get(type);

        List<FieldSerializer> fieldSer = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        try {
            Class<?> current = type;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    int mod = field.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                    if (field.isAnnotationPresent(BonsaiIgnore.class)) continue;

                    field.setAccessible(true);

                    Class<?> fType = field.getType();
                    if (fType.isEnum()) registerEnum(fType);

                    MethodHandle handle = lookup.unreflectGetter(field);
                    byte[] keyBytes = ("\"" + field.getName() + "\":").getBytes(StandardCharsets.UTF_8);
                    fieldSer.add(new FieldSerializer(keyBytes, handle));
                }
                current = current.getSuperclass();
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
                } catch (Throwable t) { throw new RuntimeException("Field Access Error", t); }
            }
            writer.writeByte(JsonWriter.OBJECT_END);
        };

        pojoWriters.put(type, fastWriter);
        return fastWriter;
    }

    private static class FieldSetter {
        final MethodHandle setter;
        final Type type;
        FieldSetter(MethodHandle setter, Type type) { this.setter = setter; this.type = type; }
    }

    @SuppressWarnings("unchecked")
    private synchronized static <T> void registerUnsafeReader(Class<T> type) {
        if (dsl.tryFindReader(type) != null) return;

        Map<String, FieldSetter> fieldMap = new HashMap<>();
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                if (f.isAnnotationPresent(BonsaiIgnore.class)) continue;

                f.setAccessible(true);
                try {
                    MethodHandle mh = lookup.unreflectSetter(f);
                    fieldMap.put(f.getName(), new FieldSetter(mh, f.getGenericType()));
                } catch (IllegalAccessException e) { throw new RuntimeException(e); }
            }
            current = current.getSuperclass();
        }

        JsonReader.ReadObject<T> fastReader = reader -> {
            try {
                T instance = (T) unsafe.allocateInstance(type);

                if (reader.last() != '{') throw reader.newParseError("Expected '{'");
                reader.getNextToken();

                while (reader.last() != '}') {
                    String key = reader.readKey();
                    FieldSetter fs = fieldMap.get(key);

                    if (fs != null) {
                        JsonReader.ReadObject<?> fieldReader = dsl.tryFindReader(fs.type);

                        if (fieldReader == null) {
                            Class<?> raw = null;
                            if (fs.type instanceof Class<?>) raw = (Class<?>) fs.type;
                            else if (fs.type instanceof ParameterizedType) raw = (Class<?>) ((ParameterizedType) fs.type).getRawType();

                            if (raw != null) {
                                if (raw.isEnum()) {
                                    registerEnum(raw);
                                } else if (!raw.getName().startsWith("java.")
                                        && !raw.isInterface()
                                        && !raw.isArray()) {
                                    registerUnsafeReader(raw);
                                }
                                fieldReader = dsl.tryFindReader(fs.type);
                            }
                        }

                        if (fieldReader != null) {
                            Object val = fieldReader.read(reader);
                            fs.setter.invoke(instance, val);
                        } else {
                            reader.skip();
                        }
                    } else {
                        reader.skip();
                    }
                    if (reader.last() == ',') reader.getNextToken();
                }
                reader.getNextToken();
                return instance;
            } catch (Throwable e) {
                if (e instanceof IOException) throw (IOException)e;
                throw new RuntimeException(e);
            }
        };

        dsl.registerReader(type, fastReader);
    }

    private static class FieldSerializer {
        final byte[] key;
        final MethodHandle handle;
        FieldSerializer(byte[] k, MethodHandle h) { key = k; handle = h; }
    }

    public static void registerType(Class<?> type) {
        if (!pojoWriters.containsKey(type)) registerPojo(type);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) return null;
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (dsl.tryFindReader(type) == null) {
                registerUnsafeReader(type);
            }
            return dsl.deserialize(type, bytes, bytes.length);
        } catch (IOException e) {
            throw new RuntimeException("JSON Deserialization Failed for " + type.getSimpleName(), e);
        }
    }
}