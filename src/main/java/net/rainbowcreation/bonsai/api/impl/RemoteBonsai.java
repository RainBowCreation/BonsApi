package net.rainbowcreation.bonsai.api.impl;

import net.rainbowcreation.bonsai.api.Bonsai;
import net.rainbowcreation.bonsai.api.BonsaiRoot;
import net.rainbowcreation.bonsai.api.internal.Connection;

import java.util.List;

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
    public List<String> getRoots() { return null; }

    @Override
    public void stop() { connection.stop(); }
}