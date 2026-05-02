package com.decisionmesh.bootstrap.eventsourcing;

import com.decisionmesh.domain.event.DomainEvent;
import java.util.List;

public interface ReplayEngine {
    void replay(List<DomainEvent> events);
}