package com.decisionmesh.portfolio.allocator;

import java.util.*;

public class PortfolioBudgetAllocator {

    public Map<UUID, Double> allocate(double totalBudget,
                                      Map<UUID, Double> priorityWeights) {

        double totalWeight = priorityWeights.values().stream().mapToDouble(Double::doubleValue).sum();

        Map<UUID, Double> allocation = new HashMap<>();
        for (Map.Entry<UUID, Double> entry : priorityWeights.entrySet()) {
            double share = (entry.getValue() / totalWeight) * totalBudget;
            allocation.put(entry.getKey(), share);
        }
        return allocation;
    }
}