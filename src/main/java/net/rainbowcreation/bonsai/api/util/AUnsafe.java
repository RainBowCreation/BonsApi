package net.rainbowcreation.bonsai.api.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

public abstract class AUnsafe {
    protected static final Unsafe unsafe;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Could not access Unsafe in RemoteTable", e);
        }
    }
}
