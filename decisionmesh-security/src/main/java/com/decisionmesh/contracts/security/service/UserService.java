package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.UserEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class UserService {

    /**
     * Creates a new external user (e.g. from Keycloak OIDC sub claim).
     * Timestamps are managed by @CreationTimestamp / @UpdateTimestamp on the entity.
     */
    public Uni<UserEntity> createExternalUser(String keycloak_sub,
                                              String email,
                                              String name) {

        return Panache.withTransaction(() -> {
            UserEntity user     = new UserEntity();
            user.email          = email;
            user.name           = name;
            user.isActive       = true;
            return user.<UserEntity>persist();
        });
    }

    /**
     * Finds a user by their external IdP user ID (e.g. Keycloak sub claim).
     * Returns Uni<null> if the ID is blank or no user is found.
     */
    public Uni<UserEntity> findByExternalUserId(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        // Using withSession ensures we have a fresh pipeline to the DB
        // even if called from within a failed transaction block.
        return Panache.withSession(() ->
                UserEntity.find("externalUserId", externalUserId).firstResult()
        );
    }
}