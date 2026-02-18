package net.rainbowcreation.bonsai.api;

import java.time.Duration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

public class BonsaiFuture<T> {
    private final CompletableFuture<T> handle;

    public BonsaiFuture(CompletableFuture<T> handle) {
        this.handle = handle;
    }

    /**
     * Non-blocking callback.
     */
    public void then(Consumer<T> action) {
        handle.thenAccept(action);
    }

    /**
     * Get blocking, indefinitely.
     */
    public T get() {
        try {
            return handle.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to get SapObject", e);
        }
    }

    /**
     * Get blocking with timeout.
     */
    public T get(Duration timeout) {
        try {
            return handle.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Timeout waiting for SapObject", e);
        }
    }

    public CompletableFuture<T> asCompletable() {
        return handle;
    }

    /**
     * Transforms the result of this future into a new type.
     * Use this instead of unwrapping to CompletableFuture.
     */
    public <U> BonsaiFuture<U> map(Function<T, U> mapper) {
        return new BonsaiFuture<>(handle.thenApply(mapper));
    }
}