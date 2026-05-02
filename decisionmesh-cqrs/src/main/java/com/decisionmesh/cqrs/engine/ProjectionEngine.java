package com.decisionmesh.cqrs.engine;

import com.decisionmesh.domain.event.DomainEvent;
import io.smallrye.mutiny.Uni;

public interface ProjectionEngine {

    Uni<Void> project(DomainEvent event);

}