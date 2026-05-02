package com.decisionmesh.explainability.graph;

import java.util.*;

public class DecisionTraceGraph {

    private final Map<UUID, List<UUID>> adjacency = new HashMap<>();

    public void link(UUID parentDecisionId, UUID childDecisionId) {
        adjacency.computeIfAbsent(parentDecisionId, k -> new ArrayList<>())
                 .add(childDecisionId);
    }

    public List<UUID> getChildren(UUID decisionId) {
        return adjacency.getOrDefault(decisionId, List.of());
    }

    public boolean hasCycle() {
        Set<UUID> visited = new HashSet<>();
        Set<UUID> stack = new HashSet<>();

        for (UUID node : adjacency.keySet()) {
            if (dfs(node, visited, stack)) return true;
        }
        return false;
    }

    private boolean dfs(UUID node, Set<UUID> visited, Set<UUID> stack) {
        if (stack.contains(node)) return true;
        if (visited.contains(node)) return false;

        visited.add(node);
        stack.add(node);

        for (UUID neighbor : adjacency.getOrDefault(node, List.of())) {
            if (dfs(neighbor, visited, stack)) return true;
        }

        stack.remove(node);
        return false;
    }
}