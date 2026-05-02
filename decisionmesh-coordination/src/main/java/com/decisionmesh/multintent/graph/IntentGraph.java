package com.decisionmesh.multintent.graph;

import com.decisionmesh.multintent.dependency.IntentDependency;

import java.util.*;

public class IntentGraph {

    private final Map<UUID, List<UUID>> adjacency = new HashMap<>();

    public void addDependency(IntentDependency dependency) {
        adjacency
            .computeIfAbsent(dependency.parentIntentId(), k -> new ArrayList<>())
            .add(dependency.childIntentId());
    }

    public List<UUID> getDependents(UUID intentId) {
        return adjacency.getOrDefault(intentId, List.of());
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