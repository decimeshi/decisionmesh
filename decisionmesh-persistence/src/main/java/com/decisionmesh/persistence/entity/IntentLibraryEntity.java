package com.decisionmesh.persistence.entity;

import com.decisionmesh.contracts.security.converter.StringListJsonConverter;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "intent_library",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_intent_library_name_category",
                columnNames = {"name", "category"}))
public class IntentLibraryEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, length = 255)
    public String name;

    @Column(nullable = false, length = 100)
    public String category;

    @Column(name = "category_label", length = 100)
    public String categoryLabel;      // "Payments" (human-readable)

    @Column(nullable = false, length = 50)
    public String vertical = "FINTECH";

    @Column(length = 500)
    public String description;

    @Column(name = "risk_level", length = 50)
    public String riskLevel;          // HIGH / MEDIUM / LOW

    @Column(name = "default_policy", length = 50)
    public String defaultPolicy;      // balanced / low_latency / high_accuracy / low_cost

    @Column(name = "sla_ms")
    public Integer slaMs;

    @Column(name = "regulatory_ref", length = 255)
    public String regulatoryRef;      // "RBI Payment Aggregator Guidelines 2022"

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "tags", columnDefinition = "text")
    public List<String> tags = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(name = "created_at", updatable = false, insertable = false)
    public OffsetDateTime createdAt;
}
