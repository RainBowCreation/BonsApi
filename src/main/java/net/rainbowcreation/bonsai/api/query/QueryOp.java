package net.rainbowcreation.bonsai.api.query;

public enum QueryOp {
    EQ("=", 0),
    NEQ("!=", 1),
    GT(">", 2),
    GTE(">=", 3),
    LT("<", 4),
    LTE("<=", 5),
    LIKE("LIKE", 6),
    IN("IN", 7);

    private final String symbol;
    private final int id;

    QueryOp(String symbol, int id) {
        this.symbol = symbol;
        this.id = id;
    }

    public String getSymbol() { return symbol; }
    public int getId() { return id; }

    public static QueryOp fromString(String op) {
        for (QueryOp v : values()) {
            if (v.symbol.equalsIgnoreCase(op)) return v;
        }
        return EQ;
    }
}