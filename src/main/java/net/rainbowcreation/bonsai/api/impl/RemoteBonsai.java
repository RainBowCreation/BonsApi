package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.Bonsai;
import net.rainbowcreation.bonsai.api.BonsaiRoot;
import net.rainbowcreation.bonsai.api.connection.Connection;

import java.util.Set;

public class RemoteBonsai implements Bonsai {
    private final Connection connection;

    public RemoteBonsai(Connection connection) {
        this.connection = connection;
    }

    @Override
    public BonsaiRoot getRoot(String db, String secret) {
        return new RemoteRoot(connection, db, secret);
    }

    @Override
    public Set<String> getRoots() { return null; }

    @Override
    public void stop() { connection.stop(); }
}