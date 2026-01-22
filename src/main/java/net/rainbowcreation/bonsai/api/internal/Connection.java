package net.rainbowcreation.bonsai.api.internal;

import net.rainbowcreation.bonsai.api.util.Stoppable;

import java.util.concurrent.CompletableFuture;

public interface Connection extends Stoppable {
    CompletableFuture<String> send(String op, String db, String table, String key, String payload);
}