package net.rainbowcreation.bonsai.api.query;

import java.util.ArrayList;
import java.util.List;

public class SearchCriteria {

    private final List<Criterion> nodes = new ArrayList<>();
    private String currentLogic = "AND";

    public static SearchCriteria create() {
        return new SearchCriteria();
    }

    public static SearchCriteria where(String field, QueryOp op, Object val) {
        return new SearchCriteria().and(field, op, val);
    }

    public SearchCriteria and(String field, QueryOp op, Object val) {
        nodes.add(new FilterNode(field, op, val));
        this.currentLogic = "AND";
        return this;
    }

    public SearchCriteria or(String field, QueryOp op, Object val) {
        nodes.add(new FilterNode(field, op, val));
        this.currentLogic = "OR";
        return this;
    }

    public SearchCriteria and(SearchCriteria sub) {
        nodes.add(new GroupNode("AND", sub.nodes));
        return this;
    }

    public SearchCriteria or(SearchCriteria sub) {
        nodes.add(new GroupNode("OR", sub.nodes));
        return this;
    }

    public List<Criterion> getNodes() { return nodes; }

    public Criterion buildRoot() {
        if (nodes.isEmpty()) return null;
        if (nodes.size() == 1) return nodes.get(0);
        return new GroupNode("AND", new ArrayList<>(nodes));
    }
}