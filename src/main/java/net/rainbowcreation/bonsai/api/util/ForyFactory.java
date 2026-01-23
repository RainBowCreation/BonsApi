package net.rainbowcreation.bonsai.api.util;

import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.config.Language;

public class ForyFactory {
    private static final ThreadSafeFory INSTANCE = Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .buildThreadSafeFory();

    public static ThreadSafeFory get() {
        return INSTANCE;
    }
}