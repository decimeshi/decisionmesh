package com.decisionmesh.governance.store;

import com.decisionmesh.governance.snapshot.PolicySnapshot;
import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface PolicySnapshotStore {

    Uni<Void> save(UUID intentId, long version, PolicySnapshot snapshot);

    Uni<PolicySnapshot> load(UUID intentId, long version);
}