/*
package com.decisionmesh.multiregion.store;

import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.multiregion.region.Region;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

public interface RegionAwareEventStore {

    Uni<Void> append(Region region,
                     UUID aggregateId,
                     long expectedVersion,
                     List<DomainEvent> events);

    Uni<List<DomainEvent>> load(Region region,
                                UUID aggregateId);
}*/
