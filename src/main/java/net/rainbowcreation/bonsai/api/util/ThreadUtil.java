package net.rainbowcreation.bonsai.api.util;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadUtil {
    private static final Method VIRTUAL_EXECUTOR = probeVirtualThreads();

    private static Method probeVirtualThreads() {
        try {
            return Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static ExecutorService virtualExecutor() {
        try {
            return (ExecutorService) VIRTUAL_EXECUTOR.invoke(null);
        } catch (Exception e) {
            return Executors.newCachedThreadPool();
        }
    }

    public static ExecutorService newWorkerPool(int size) {
        if (VIRTUAL_EXECUTOR != null) return virtualExecutor();
        return Executors.newFixedThreadPool(size);
    }

    public static ExecutorService newSingleThreadExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    public static ExecutorService newTaskExecutor() {
        if (VIRTUAL_EXECUTOR != null) return virtualExecutor();
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
