package com.decisionmesh.governance.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events",
        indexes = @Index(name = "idx_processed_events_event_id",
                columnList = "event_id", unique = true))
public class ProcessedEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id")
    public UUID id;

    @Column(name = "event_id", nullable = false, unique = true, length = 255)
    public String eventId;

    @Column(name = "processed_at", nullable = false)
    public Instant processedAt;
}