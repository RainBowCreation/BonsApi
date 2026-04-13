package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.util.Stoppable;

import java.util.concurrent.CompletableFuture;

public interface Connection extends Stoppable {
    CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags);

    default void setInvalidationCallback(InvalidationCallback callback) {
    }

    /**
     * True if this transport can deliver server-pushed invalidation / change-event
     * frames. Client read-through caches must not be enabled on transports that
     * return false, or stale reads will persist indefinitely.
     */
    default boolean supportsInvalidation() {
        return false;
    }

    /**
     * Per-database HMAC authentication. Sent on every underlying TCP connection
     * that reaches a database with a non-empty password. No-op for transports
     * that carry credentials out-of-band (e.g. HTTP Bearer).
     *
     * @throws RuntimeException if the server rejects the HMAC.
     */
    default void authenticateDb(String dbName, String secret) {
    }
}
