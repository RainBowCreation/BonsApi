package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.connection.RequestOp;
import net.rainbowcreation.bonsai.util.Stoppable;

import java.util.concurrent.CompletableFuture;

public interface Connection extends Stoppable {
    CompletableFuture<byte[]> send(RequestOp op, short dbId, short tableId, String key, byte[] payload, byte flags);

    default void setInvalidationCallback(InvalidationCallback callback) {
    }
}
