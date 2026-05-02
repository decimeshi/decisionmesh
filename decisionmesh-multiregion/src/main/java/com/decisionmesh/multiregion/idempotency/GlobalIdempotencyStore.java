package com.decisionmesh.multiregion.idempotency;

import io.smallrye.mutiny.Uni;

public interface GlobalIdempotencyStore {

    Uni<Boolean> register(String idempotencyKey);

    Uni<Boolean> exists(String idempotencyKey);
}