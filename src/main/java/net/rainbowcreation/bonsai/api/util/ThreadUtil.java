package net.rainbowcreation.bonsai.api.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadUtil {
    public static ExecutorService newWorkerPool(int multiplier) {
        return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * multiplier);
    }

    public static ExecutorService newSingleThreadExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    public static ExecutorService newTaskExecutor() {
        return Executors.newCachedThreadPool();
    }

    public static ExecutorService newCachedThreadPool() {
        return Executors.newCachedThreadPool();
    }

    public static ExecutorService newFixedThreadPool(int size) {
        return Executors.newFixedThreadPool(size);
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    public static ThreadFactory createThreadFactory(String namePrefix) {
        return new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
                if (t.isDaemon()) t.setDaemon(false);
                if (t.getPriority() != Thread.NORM_PRIORITY) t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        };
    }
}
