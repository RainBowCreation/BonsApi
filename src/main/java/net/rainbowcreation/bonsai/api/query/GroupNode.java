package net.rainbowcreation.bonsai.api.query;

import java.util.List;

public class GroupNode implements Criterion {
    public String logic;
    public List<Criterion> children;

    public GroupNode(String logic, List<Criterion> children) {
        this.logic = logic;
        this.children = children;
    }
}