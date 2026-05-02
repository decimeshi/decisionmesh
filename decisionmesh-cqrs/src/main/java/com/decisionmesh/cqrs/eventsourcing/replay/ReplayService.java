package com.decisionmesh.cqrs.eventsourcing.replay;

import com.decisionmesh.domain.event.DomainEvent;
import java.util.List;

public interface ReplayService<T> {

    T rehydrate(List<DomainEvent> events);

}