package com.decisionmesh.bootstrap.service;

import com.decisionmesh.bootstrap.dto.CategoryResponse;
import com.decisionmesh.persistence.entity.IntentLibraryEntity;
import com.decisionmesh.persistence.repository.IntentLibraryRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class IntentLibraryService {

    @Inject IntentLibraryRepository repo;

    public Uni<List<IntentLibraryEntity>> getByVertical(String vertical) {
        return repo.list("vertical = ?1 and isActive = true", vertical);
    }

    public Uni<List<IntentLibraryEntity>> getByVerticalAndCategory(
            String vertical, String category) {
        return repo.list(
                "vertical = ?1 and category = ?2 and isActive = true",
                vertical, category);
    }

    public Uni<List<CategoryResponse>> getCategorySummaries(String vertical) {
        return repo.getSession().flatMap(session ->
                session.createNativeQuery("""
            SELECT category, category_label,
                   CAST(COUNT(*) AS INTEGER) AS cnt,
                   MAX(risk_level), MIN(regulatory_ref)
            FROM intent_library
            WHERE vertical = :v AND is_active = true
            GROUP BY category, category_label
            ORDER BY category
        """).setParameter("v", vertical)
                        .getResultList()
        ).map(rows -> rows.stream().map(row -> {
            Object[] r = (Object[]) row;
            // COUNT(*) may return BigInteger, Long, or Integer depending on driver
            int count = 0;
            if (r[2] != null) {
                try { count = ((Number) r[2]).intValue(); }
                catch (Exception e) { count = Integer.parseInt(r[2].toString()); }
            }
            return new CategoryResponse(
                    (String) r[0],   // category
                    (String) r[1],   // categoryLabel
                    count,           // count — safely cast
                    (String) r[3],   // maxRiskLevel
                    (String) r[4]    // regulatoryRef
            );
        }).toList());
    }

    public Uni<List<IntentLibraryEntity>> search(
            String vertical, String query, String tag, String risk) {
        // Use Panache query builder
        StringBuilder q = new StringBuilder(
                "vertical = ?1 and isActive = true");
        List<Object> params = new ArrayList<>();
        params.add(vertical);

        if (query != null && !query.isBlank()) {
            q.append(" and (lower(name) like ?")
                    .append(params.size() + 1)
                    .append(" or lower(description) like ?")
                    .append(params.size() + 2)
                    .append(")");
            String like = "%" + query.toLowerCase() + "%";
            params.add(like);
            params.add(like);
        }
        if (risk != null && !risk.isBlank()) {
            q.append(" and riskLevel = ?").append(params.size() + 1);
            params.add(risk.toUpperCase());
        }

        return repo.list(q.toString(), params.toArray());
    }

    public Uni<IntentLibraryEntity> getById(UUID id) {
        return repo.findById(id);
    }
}