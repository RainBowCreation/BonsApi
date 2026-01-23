package net.rainbowcreation.bonsai.api.connection;

import net.rainbowcreation.bonsai.api.util.Stoppable;

import java.util.concurrent.CompletableFuture;

public interface Connection extends Stoppable {
    CompletableFuture<byte[]> send(String op, String db, String table, String key, byte[] payload);
}