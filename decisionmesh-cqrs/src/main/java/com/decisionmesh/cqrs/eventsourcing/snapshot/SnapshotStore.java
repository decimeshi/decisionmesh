package com.decisionmesh.cqrs.eventsourcing.snapshot;

import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface SnapshotStore<T> {

    Uni<Void> save(UUID aggregateId, long version, T snapshot);

    Uni<T> load(UUID aggregateId);

}