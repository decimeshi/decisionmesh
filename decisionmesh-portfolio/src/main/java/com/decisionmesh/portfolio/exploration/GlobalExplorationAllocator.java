package com.decisionmesh.portfolio.exploration;

import java.util.*;

public class GlobalExplorationAllocator {

    public Map<UUID, Double> allocateExploration(double globalEpsilon,
                                                 Map<UUID, Double> uncertaintyScores) {

        double totalUncertainty = uncertaintyScores.values()
                .stream().mapToDouble(Double::doubleValue).sum();

        Map<UUID, Double> allocation = new HashMap<>();
        for (Map.Entry<UUID, Double> entry : uncertaintyScores.entrySet()) {
            double share = (entry.getValue() / totalUncertainty) * globalEpsilon;
            allocation.put(entry.getKey(), share);
        }
        return allocation;
    }
}