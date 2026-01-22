package net.rainbowcreation.bonsai.api.query;

public class FilterNode implements Criterion {
    public String field;
    public int op;
    public Object value;

    public FilterNode(String field, QueryOp op, Object value) {
        this.field = field;
        this.op = op.getId();
        this.value = value;
    }
}