package net.rainbowcreation.bonsai.api.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

public class ThreadUtil {
    public static ExecutorService newWorkerPool(int multiplier) {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    public static ExecutorService newSingleThreadExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    public static ExecutorService newTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
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
        return Executors.defaultThreadFactory();
    }
}
