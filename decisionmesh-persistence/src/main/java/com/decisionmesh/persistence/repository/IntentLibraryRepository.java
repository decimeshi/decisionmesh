package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.IntentLibraryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import io.smallrye.mutiny.Uni;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class IntentLibraryRepository implements PanacheRepositoryBase<IntentLibraryEntity, UUID> {

    // ── Original methods kept ─────────────────────────────────────────────────

    public Uni<List<IntentLibraryEntity>> findAllIntents() {
        return listAll();
    }

    public Uni<List<IntentLibraryEntity>> findByCategory(String category) {
        return list("category = ?1 and isActive = true", category);
    }

    public Uni<IntentLibraryEntity> findByNameAndCategory(String name, String category) {
        return find("name = ?1 and category = ?2", name, category)
                .firstResult();
    }

    public Uni<IntentLibraryEntity> findByName(String name) {
        return find("name", name).firstResult();
    }

    public Uni<List<String>> findDistinctCategories() {
        return find("select distinct f.category from FintechEntity f")
                .project(String.class)
                .list();
    }

    // ── New vertical-aware methods ────────────────────────────────────────────

    /** All active intents for a vertical — used by getAll(vertical) */
    public Uni<List<IntentLibraryEntity>> findByVertical(String vertical) {
        return list("vertical = ?1 and isActive = true", vertical);
    }

    /** Active intents for a vertical + category — used by byCategory() */
    public Uni<List<IntentLibraryEntity>> findByVerticalAndCategory(
            String vertical, String category) {
        return list("vertical = ?1 and category = ?2 and isActive = true",
                vertical, category);
    }

    /** Distinct categories for a vertical — used by categories() dropdown */
    public Uni<List<String>> findDistinctCategoriesByVertical(String vertical) {
        return find(
                "select distinct f.category from FintechEntity f " +
                        "where f.vertical = ?1 and f.isActive = true", vertical)
                .project(String.class)
                .list();
    }
    public Uni<IntentLibraryEntity> findByNameCategoryAndVertical(
            String name, String category, String vertical) {

        return find("name = ?1 and category = ?2 and vertical = ?3 and isActive = true",
                name, category, vertical)
                .firstResult();
    }
}