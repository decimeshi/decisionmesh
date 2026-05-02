package com.decisionmesh.persistence.entity;

import com.decisionmesh.contracts.security.converter.StringListJsonConverter;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "vertical_config")
public class VerticalConfigEntity extends PanacheEntityBase {

    @Id
    public String vertical; // "FINTECH", "LEGAL", "HEALTHCARE"

    public String displayName;        // "Financial Services"
    public String description;        // "Payments, lending, fraud..."
    public String iconKey;            // "fintech" → maps to icon in UI
    public Integer defaultSlaMs;      // 200
    public String defaultRiskLevel;   // "MEDIUM"
    public String defaultPolicy;      // "balanced"
    public Integer auditRetentionDays;// 1095 (RBI = 3 years)
    public boolean active;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "regulatory_refs", columnDefinition = "jsonb")
    public List<String> regulatoryRefs = new ArrayList<>();

    // Object fields — @JdbcTypeCode is fine, just needs correct Java type
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_risk_map", columnDefinition = "jsonb")
    public Map<String, String> categoryRiskMap = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sla_by_category_ms", columnDefinition = "jsonb")
    public Map<String, Integer> slaByCategoryMs = new HashMap<>();
}
