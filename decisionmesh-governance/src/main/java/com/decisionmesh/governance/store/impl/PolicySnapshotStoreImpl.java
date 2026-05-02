package com.decisionmesh.governance.store.impl;


import com.decisionmesh.governance.snapshot.PolicySnapshot;
import com.decisionmesh.governance.store.PolicySnapshotStore;
import com.decisionmesh.governance.entity.PolicySnapshotEntity;
import com.decisionmesh.governance.repository.PolicySnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class PolicySnapshotStoreImpl implements PolicySnapshotStore {

    @Inject
    PolicySnapshotRepository repository;

    ObjectMapper mapper = new ObjectMapper();


    @Override
    public Uni<Void> save(UUID intentId, long version, PolicySnapshot snapshot) {

        try {
            String json = mapper.writeValueAsString(snapshot);

            PolicySnapshotEntity entity = new PolicySnapshotEntity();
            entity.intentId = intentId;
            entity.version = version;
            entity.snapshotJson = json;

            return repository.persist(entity).replaceWithVoid();

        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<PolicySnapshot> load(UUID intentId, long version) {

        return repository.findByIntentAndVersion(intentId, version)
                .onItem().ifNull().failWith(() -> new RuntimeException("Snapshot not found"))
                .map(entity -> {
                    try {
                        return mapper.readValue(entity.snapshotJson, PolicySnapshot.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
