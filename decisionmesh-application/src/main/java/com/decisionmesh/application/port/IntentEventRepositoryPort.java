package com.decisionmesh.application.port;

import com.decisionmesh.domain.event.DomainEvent;
import io.smallrye.mutiny.Uni;
import java.util.List;

public interface IntentEventRepositoryPort {

    Uni<Void> appendAll(List<DomainEvent> events);
}