package com.decisionmesh.multiregion.partition;

import com.decisionmesh.multiregion.region.Region;
import java.util.UUID;

public class PartitionResolver {

    private final Region[] regions = Region.values();

    public Region resolve(String tenantId, UUID intentId) {
        int hash = Math.abs((tenantId + intentId.toString()).hashCode());
        return regions[hash % regions.length];
    }
}