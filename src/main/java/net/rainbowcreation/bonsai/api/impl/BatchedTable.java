package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.BonsaiFuture;
import net.rainbowcreation.bonsai.BonsaiTable;
import net.rainbowcreation.bonsai.query.Query;
import net.rainbowcreation.bonsai.util.ThreadUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Auto-batching wrapper for BonsaiTable.
 * Accumulates individual get() calls and flushes them as a single MGET every {@code delayMs} milliseconds.
 */
public class BatchedTable<T> implements BonsaiTable<T> {
    private final RemoteTable<T> delegate;
    private final long delayMs;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private List<String> pendingKeys = new ArrayList<>();
    private List<CompletableFuture<T>> pendingFutures = new ArrayList<>();
    private ScheduledFuture<?> scheduledFlush;

    private static final int MAX_BATCH_SIZE = 500;

    public BatchedTable(RemoteTable<T> delegate, long delayMs) {
        this.delegate = delegate;
        this.delayMs = delayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            ThreadUtil.createThreadFactory("Bonsai-Batcher", true)
        );
    }

    @Override
    public BonsaiFuture<T> getAsync(String key) {
        CompletableFuture<T> future = new CompletableFuture<>();
        boolean flushNow = false;

        synchronized (lock) {
            pendingKeys.add(key);
            pendingFutures.add(future);

            if (pendingKeys.size() >= MAX_BATCH_SIZE) {
                flushNow = true;
            } else if (scheduledFlush == null) {
                scheduledFlush = scheduler.schedule(this::flush, delayMs, TimeUnit.MILLISECONDS);
            }
        }

        if (flushNow) {
            flush();
        }

        return new BonsaiFuture<>(future);
    }

    @Override
    public BonsaiFuture<Map<String, T>> getAsync(List<String> keys) {
        return delegate.getAsync(keys);
    }

    private void flush() {
        List<String> keys;
        List<CompletableFuture<T>> futures;

        synchronized (lock) {
            if (pendingKeys.isEmpty()) return;
            keys = pendingKeys;
            futures = pendingFutures;
            pendingKeys = new ArrayList<>();
            pendingFutures = new ArrayList<>();
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
        }

        // Single key
        if (keys.size() == 1) {
            delegate.getAsync(keys.get(0)).asCompletable()
                .whenComplete((val, ex) -> {
                    if (ex != null) futures.get(0).completeExceptionally(ex);
                    else futures.get(0).complete(val);
                });
            return;
        }

        // Multi-key — use MGET
        delegate.getAsync(keys).asCompletable()
            .whenComplete((resultMap, ex) -> {
                if (ex != null) {
                    for (CompletableFuture<T> f : futures) {
                        f.completeExceptionally(ex);
                    }
                    return;
                }
                for (int i = 0; i < keys.size(); i++) {
                    T val = resultMap != null ? resultMap.get(keys.get(i)) : null;
                    futures.get(i).complete(val);
                }
            });
    }

    @Override
    public BonsaiFuture<Void> setAsync(String key, T value) {
        return delegate.setAsync(key, value);
    }

    @Override
    public BonsaiFuture<Void> setAsync(String key, T value, long ttl, TimeUnit unit) {
        return delegate.setAsync(key, value, ttl, unit);
    }

    @Override
    public BonsaiFuture<Void> deleteAsync(String key) {
        return delegate.deleteAsync(key);
    }

    @Override
    public BonsaiFuture<Boolean> existsAsync(String key) {
        return delegate.existsAsync(key);
    }

    @Override
    public Query<T> find() {
        return delegate.find();
    }

    @Override
    public BonsaiTable<T> withBatch(long delayMs) {
        return new BatchedTable<>(delegate, delayMs);
    }
}
