package net.rainbowcreation.bonsai.api.util;

import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.config.Language;
import org.apache.fory.logging.LogLevel;
import org.apache.fory.logging.LoggerFactory;

public class ForyFactory {
    private static final ThreadSafeFory INSTANCE;

    static {
        LoggerFactory.setLogLevel(LogLevel.ERROR_LEVEL);
        INSTANCE = Fory.builder()
                .withLanguage(Language.JAVA)
                .requireClassRegistration(false)
                .buildThreadSafeFory();
    }

    public static ThreadSafeFory get() {
        return INSTANCE;
    }
}