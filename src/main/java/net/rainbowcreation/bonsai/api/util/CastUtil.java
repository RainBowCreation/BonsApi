package net.rainbowcreation.bonsai.api.util;

public class CastUtil {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object coerce(Object val, Class<?> target) {
        if (val == null) return null;

        if (target.isInstance(val)) return val;

        //  Number Conversion
        if (val instanceof Number) {
            Number n = (Number) val;
            if (target == int.class || target == Integer.class) return n.intValue();
            if (target == long.class || target == Long.class) return n.longValue();
            if (target == double.class || target == Double.class) return n.doubleValue();
            if (target == float.class || target == Float.class) return n.floatValue();
            if (target == short.class || target == Short.class) return n.shortValue();
            if (target == byte.class || target == Byte.class) return n.byteValue();

            // Number -> Boolean (0 = false, anything else = true)
            if (target == boolean.class || target == Boolean.class) return n.intValue() != 0;
        }

        if (target == String.class) {
            return val.toString();
        }

        // Boolean Parsing (String "true" -> Boolean)
        if (target == boolean.class || target == Boolean.class) {
            if (val instanceof String) return Boolean.parseBoolean((String) val);
        }

        // Enum Conversion (String "MAGE" -> Enum.MAGE)
        if (target.isEnum() && val instanceof String) {
            try {
                return Enum.valueOf((Class<Enum>) target, (String) val);
            } catch (IllegalArgumentException e) {
                // Returning null allows partial loading rather than crash.
                System.err.println("[CastUtil] Warning: Enum constant " + val + " not found in " + target.getSimpleName());
                return null;
            }
        }

        return val;
    }
}