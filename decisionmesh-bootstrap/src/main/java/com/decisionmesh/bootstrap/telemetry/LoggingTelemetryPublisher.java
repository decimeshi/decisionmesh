package com.decisionmesh.bootstrap.telemetry;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.persistence.PrimaryKeyJoinColumns;

import java.util.UUID;
@Alternative
@Priority(0)
@ApplicationScoped
public class LoggingTelemetryPublisher {

    public Uni<Void> publish(String phase, UUID tenantId, UUID intentId, long version) {
        Log.infof("Intent Telemetry: phase=%s, tenant=%s, intent=%s, version=%d",
                phase, tenantId, intentId, version);
        return Uni.createFrom().voidItem();
    }
}