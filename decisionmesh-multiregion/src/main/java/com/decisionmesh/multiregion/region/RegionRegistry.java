package com.decisionmesh.multiregion.region;

import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface RegionRegistry {

    Uni<Region> getHomeRegion(UUID intentId);

    Uni<Void> assignHomeRegion(UUID intentId, String tenantId, Region region);

    Uni<Void> failover(UUID intentId, Region newRegion);

}