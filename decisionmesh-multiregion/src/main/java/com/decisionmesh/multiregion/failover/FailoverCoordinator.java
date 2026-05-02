package com.decisionmesh.multiregion.failover;

import com.decisionmesh.multiregion.region.Region;
import com.decisionmesh.multiregion.region.RegionRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.UUID;

public class FailoverCoordinator {

    @Inject RegionRegistry regionRegistry;

    public Uni<Void> triggerFailover(UUID intentId, Region targetRegion) {
        return regionRegistry.failover(intentId, targetRegion);
    }
}