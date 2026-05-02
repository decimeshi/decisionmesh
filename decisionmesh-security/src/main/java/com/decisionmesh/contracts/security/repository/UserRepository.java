package com.decisionmesh.contracts.security.repository;


import com.decisionmesh.contracts.security.entity.UserEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Reactive Panache repository for UserEntity.
 * * This repository provides a clean abstraction for user-related database operations
 * using the non-blocking Mutiny API.
 */
@ApplicationScoped
public class UserRepository implements PanacheRepositoryBase<UserEntity, UUID> {

    /**
     * Finds a user by their unique email address.
     * * @param email The email to search for.
     * @return A Uni containing the UserEntity if found, or null otherwise.
     */
    public Uni<UserEntity> findByEmail(String email) {
        return find("email", email).firstResult();
    }

    /**
     * Finds a user by their external Identity Provider ID (e.g., Keycloak 'sub' claim).
     * * @param externalUserId The UUID string from the IdP.
     * @return A Uni containing the UserEntity if found, or null otherwise.
     */
    public Uni<UserEntity> findByExternalUserId(String externalUserId) {
        return find("externalUserId", externalUserId).firstResult();
    }

    public Uni<UserEntity> findByExternalId(String externalId) {
        return find("externalUserId", externalId).firstResult();
    }

    /**
     * Checks if a user exists by their external ID.
     * Useful for idempotency checks during onboarding.
     */
    public Uni<Boolean> existsByExternalId(String externalUserId) {
        return findByExternalUserId(externalUserId)
                .onItem().transform(user -> user != null);
    }

    /**
     * Finds an active user by their internal UUID.
     */
    public Uni<UserEntity> findActiveById(UUID userId) {
        return find("userId = ?1 and isActive = true", userId).firstResult();
    }
}