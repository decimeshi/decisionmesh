package com.decisionmesh.llm.service;

import com.decisionmesh.llm.dto.AdapterRequest;
import com.decisionmesh.persistence.entity.AdapterEntity;
import com.decisionmesh.persistence.repository.AdapterRepository;
import io.smallrye.mutiny.Uni;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AdapterService {

    @Inject
    AdapterRepository repository;

    // ── LIST ──────────────────────────────────────────────────────────────────

    public Uni<List<AdapterEntity>> list(UUID tenantId) {
        return repository.findByTenant(tenantId);
    }

    // ── CREATE ────────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<AdapterEntity> create(UUID tenantId, AdapterRequest req) {
        validate(req);

        AdapterEntity e    = new AdapterEntity();
        e.tenantId         = tenantId;
        e.name             = req.name();
        e.provider         = normalizeProvider(req.provider());
        e.adapterType      = req.adapterType() != null ? req.adapterType() : "LLM";
        e.modelId          = req.modelId();
        e.region           = req.region();
        e.isActive         = req.isActive() != null ? req.isActive() : true;
        e.config           = req.configJson();
        e.capabilityFlags  = req.capabilityFlagsJson();
        e.allowedIntentTypes = req.allowedIntentTypesJson();

        return repository.persist(e);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<AdapterEntity> update(UUID tenantId, UUID id, AdapterRequest req) {
        validate(req);

        return repository.findById(tenantId, id)
                .onItem().ifNull().failWith(() -> new RuntimeException("Adapter not found"))
                .flatMap(e -> {
                    e.name        = req.name();
                    e.modelId     = req.modelId();
                    e.provider    = normalizeProvider(req.provider());
                    e.adapterType = req.adapterType();
                    e.region      = req.region();
                    if (req.isActive() != null)           e.isActive           = req.isActive();
                    if (req.config() != null)             e.config             = req.configJson();
                    if (req.capabilityFlags() != null)    e.capabilityFlags    = req.capabilityFlagsJson();
                    if (req.allowedIntentTypes() != null) e.allowedIntentTypes = req.allowedIntentTypesJson();
                    return repository.persist(e);
                });
    }

    // ── TOGGLE ────────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<AdapterEntity> toggle(UUID tenantId, UUID id, boolean active) {
        return repository.findById(tenantId, id)
                .onItem().ifNull().failWith(() -> new RuntimeException("Adapter not found"))
                .flatMap(e -> { e.isActive = active; return repository.persist(e); });
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<Void> delete(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .onItem().ifNull().failWith(() -> new RuntimeException("Adapter not found"))
                .flatMap(repository::delete);
    }

    // ── PRIVATE ───────────────────────────────────────────────────────────────

    private void validate(AdapterRequest req) {
        if (req.name() == null || req.name().isBlank())
            throw new BadRequestException("Adapter name is required");
    }

    private String normalizeProvider(String provider) {
        if (provider == null) return "CUSTOM";
        String val = provider.toUpperCase();
        if (val.equals("GOOGLE")) return "GEMINI";
        if (val.equals("AZURE"))  return "AZURE_OPENAI";
        return val;
    }
}