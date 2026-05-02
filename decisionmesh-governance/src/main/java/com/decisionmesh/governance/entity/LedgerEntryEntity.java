package com.decisionmesh.governance.entity;


import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entry")
public class LedgerEntryEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    public UUID intentId;
    public String tenantId;
    public long aggregateVersion;

    public UUID eventId;
    public String eventType;

    @Lob
    public String policySnapshotJson;

    @Lob
    public String budgetSnapshotJson;

    @Lob
    public String slaSnapshotJson;

    public String previousHash;
    public String currentHash;

    public Instant timestamp;
}
