package com.decisionmesh.persistence.memory;

import com.decisionmesh.application.port.IntentEventRepositoryPort;
import com.decisionmesh.domain.event.DomainEvent;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
@DefaultBean
public class InMemoryIntentEventRepository implements IntentEventRepositoryPort {

    private final List<DomainEvent> store = new CopyOnWriteArrayList<>();

    @Override
    public Uni<Void> appendAll(List<DomainEvent> events) {

        if (events == null || events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        // Defensive copy to prevent external mutation
        store.addAll(new ArrayList<>(events));

        return Uni.createFrom().voidItem();
    }

    // Optional helper for testing
    public List<DomainEvent> findAll() {
        return List.copyOf(store);
    }

    // Optional helper for clearing (tests)
    public void clear() {
        store.clear();
    }
}
