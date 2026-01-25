package net.rainbowcreation.bonsai.api;

public class BonsaiRequest {
    public long id;
    public final String op;
    public final String db;
    public final String table;
    public final String key;
    public final byte[] payload;

    public BonsaiRequest(String op, String db, String table, String key, byte[] payload) {
        this.op = op;
        this.db = db;
        this.table = table;
        this.key = key;
        this.payload = payload;
    }
}