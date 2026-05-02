package com.decisionmesh.common.repository;


import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

/**
 * Base reactive repository for DecisionMesh.
 * Provides common helpers on top of PanacheRepositoryBase.
 *
 * @param <T> Entity type
 */
public interface ReactivePanacheRepository<T>
        extends PanacheRepositoryBase<T, UUID> {

    /**
     * Check if entity exists by ID
     */
    default Uni<Boolean> existsById(UUID id) {
        return findById(id)
                .onItem().transform(entity -> entity != null);
    }

    /**
     * Delete entity by ID safely
     */
    default Uni<Boolean> deleteByIdSafe(UUID id) {
        return findById(id)
                .onItem().ifNotNull().transformToUni(entity ->
                        delete(entity).replaceWith(true)
                )
                .onItem().ifNull().continueWith(false);
    }

    /**
     * Find or fail with custom message
     */
    default Uni<T> findByIdOrFail(UUID id, String message) {
        return findById(id)
                .onItem().ifNull().failWith(() ->
                        new RuntimeException(message)
                );
    }
}