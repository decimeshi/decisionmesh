package com.decisionmesh.governance.entity;


import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "policy_snapshot")
public class PolicySnapshotEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    public UUID intentId;
    public long version;

    @Lob
    public String snapshotJson;
}
