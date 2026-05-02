package com.decisionmesh.bootstrap;

import com.decisionmesh.persistence.entity.IntentLibraryEntity;
import com.decisionmesh.persistence.repository.IntentLibraryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads intent library JSON files from the classpath on startup.
 *
 * Threading model (why this is non-trivial in Quarkus reactive):
 *
 *   @PostConstruct  → CDI bootstrap thread, Vert.x not ready → deadlock
 *   StartupEvent    → Quarkus Main Thread, NOT a Vert.x thread
 *                     → @WithTransaction calls SessionOperations.vertxContext()
 *                     → throws "No current Vertx context found"
 *
 * Fix: VertxContextSupport.subscribeAndAwait() runs the Uni on a proper
 * Vert.x duplicated context, then blocks the startup thread until the
 * pipeline completes. This is the Quarkus-recommended pattern for reactive
 * work that must finish before the application accepts traffic.
 */
@ApplicationScoped
public class IntentLibraryBootstrap {

    @Inject
    IntentLibraryRepository repo;

    // ── Startup ───────────────────────────────────────────────────────────────

    void onStart(@Observes StartupEvent event) throws Throwable {


        // ── Seed intent library ──────────────────────────────────────────
        VertxContextSupport.subscribeAndAwait(() -> loadVertical("fintech"));
    }



    // ── Load one vertical ─────────────────────────────────────────────────────

    Uni<Integer> loadVertical(String verticalKey) {
        String resourcePath = "intent-library-" + verticalKey + ".json";

        InputStream is = getClass().getClassLoader()
                .getResourceAsStream(resourcePath);

        if (is == null) {
            Log.warnf("[Bootstrap] Schema not found on classpath: %s — skipping", resourcePath);
            return Uni.createFrom().item(0);
        }

        List<IntentLibraryEntity> toInsert = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root       = mapper.readTree(is);

            String vertical    = root.get("vertical").asText();
            String displayName = root.get("displayName").asText();
            String version     = root.get("version").asText();

            Log.infof("[Bootstrap] Parsing %s v%s (%s)", vertical, version, displayName);

            for (JsonNode domain : root.get("domains")) {
                String category      = domain.get("category").asText();
                String catLabel      = domain.get("displayName").asText();
                int    domainSlaMs   = domain.path("defaultSlaMs").asInt(200);
                String domainRisk    = domain.path("defaultRiskLevel").asText("MEDIUM");
                String regulatoryRef = domain.path("regulatoryRef").asText(null);

                for (JsonNode intentNode : domain.get("intents")) {
                    String name        = intentNode.get("name").asText();
                    String description = intentNode.path("description").asText(null);
                    String riskLevel   = intentNode.path("riskLevel").asText(domainRisk);
                    int    slaMs       = intentNode.path("slaMs").asInt(domainSlaMs);
                    String policy      = intentNode.path("defaultPolicy").asText("balanced");
                    String tagsJson    = intentNode.has("tags") ? intentNode.get("tags").toString() : "[]";

                    IntentLibraryEntity entity = new IntentLibraryEntity();
                    entity.name          = name;
                    entity.category      = category;
                    entity.categoryLabel = catLabel;
                    entity.vertical      = vertical;
                    entity.description   = description;
                    entity.riskLevel     = riskLevel;
                    entity.slaMs         = slaMs;
                    entity.defaultPolicy = policy;
                    entity.regulatoryRef = regulatoryRef;

                    List<String> tags = new ArrayList<>();
                    if (intentNode.has("tags")) {
                        intentNode.get("tags").forEach(t -> tags.add(t.asText()));
                    }
                    entity.tags = tags;
                    entity.isActive      = true;

                    toInsert.add(entity);
                }
            }

            Log.infof("[Bootstrap] Parsed %d intents for %s — checking DB...",
                    toInsert.size(), vertical);

        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new RuntimeException("[Bootstrap] Failed to parse " + resourcePath, e));
        }

        return Multi.createFrom().iterable(toInsert)
                .onItem().transformToUniAndConcatenate(this::upsertIfAbsent)
                .filter(inserted -> inserted)
                .collect().asList()
                .map(inserted -> {
                    int skipped = toInsert.size() - inserted.size();
                    Log.infof("[Bootstrap] %s complete: inserted=%d skipped=%d",
                            toInsert.isEmpty() ? "?" : toInsert.get(0).vertical,
                            inserted.size(), skipped);
                    return inserted.size();
                });
    }

    // ── Idempotent single insert ──────────────────────────────────────────────

    @WithTransaction
    Uni<Boolean> upsertIfAbsent(IntentLibraryEntity entity) {
        return repo.findByNameAndCategory(entity.name, entity.category)
                .flatMap(existing -> {
                    if (existing != null) {
                        return Uni.createFrom().item(false);   // already present — skip
                    }
                    return repo.persist(entity)
                            .map(saved -> {
                                Log.debugf("[Bootstrap] Inserted [%s] %s",
                                        entity.category, entity.name);
                                return true;
                            });
                });
    }
}