package net.rainbowcreation.bonsai.api.query;

import java.util.Map;

public class UpdatePayload {
    public Criterion where;
    public Map<String, Object> set;

    public UpdatePayload(Criterion where, Map<String, Object> set) {
        this.where = where;
        this.set = set;
    }
}