package com.decisionmesh.application.idempotency;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@DefaultBean
public class InMemoryIdempotencyService implements IdempotencyService {

    private final Set<String> registry = ConcurrentHashMap.newKeySet();

    @Override
    public Uni<Boolean> checkAndRegister(UUID tenantId, String idempotencyKey) {

        return Uni.createFrom().item(() -> {

            if (tenantId == null) {
                throw new IllegalArgumentException("TenantId must not be null");
            }

            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("Invalid idempotency key");
            }

            String compositeKey = buildKey(tenantId, idempotencyKey);

            // add() returns false if already present
            return registry.add(compositeKey);
        });
    }

    private String buildKey(UUID tenantId, String key) {
        return tenantId + ":" + key;
    }
}
