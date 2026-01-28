package net.rainbowcreation.bonsai.api.connection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public enum RequestOp {
    INVALIDATE("INVALIDATE", -1),
    // std ops
    GET("GET", 0),
    SET("SET", 1),
    DELETE("DELETE", 2),
    EXISTS("EXISTS", 3),

    // query ops
    QUERY_GET("QUERY_GET", 10),
    QUERY_UPDATE("QUERY_UPDATE", 11),
    QUERY_DELETE("QUERY_DELETE", 12),
    QUERY_COUNT("QUERY_COUNT", 13),

    // management ops
    REGISTER_SCHEMA("REGISTER_SCHEMA", 20),
    SUBSCRIBE("SUBSCRIBE", 21);

    private final String symbol;
    private final int id;

    private static final RequestOp[] ID_MAP = new RequestOp[32];
    private static final Map<String, RequestOp> VALUE_MAP = new ConcurrentHashMap<>(32);

    static {
        for (RequestOp op : values()) {
            if (op.id >= 0 && op.id < ID_MAP.length) {
                ID_MAP[op.id] = op;
                VALUE_MAP.put(op.symbol, op);
            }
        }
    }

    RequestOp(String symbol, int id) {
        this.symbol = symbol;
        this.id = id;
    }

    public int getId() { return id; }

    public String getSymbol() { return symbol; }

    public byte getByte() { return (byte) id; }

    public static RequestOp fromId(int id) {
        if (id < 0 || id >= ID_MAP.length) return GET;
        return ID_MAP[id];
    }

    public static RequestOp fromSymbol(String symbol) {
        return VALUE_MAP.get(symbol);
    }
}
