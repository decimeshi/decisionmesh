
package com.decisionmesh.persistence.repository;

import com.decisionmesh.contracts.security.entity.ProjectEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProjectRepository implements PanacheRepositoryBase<ProjectEntity, UUID> {

    public Uni<List<ProjectEntity>> findByTenantId(UUID tenantId) {
        return list("tenantId = ?1 ORDER BY isDefault DESC, name ASC", tenantId);
    }

    public Uni<ProjectEntity> findDefaultProject(UUID tenantId) {
        return find("tenantId = ?1 AND isDefault = true", tenantId).firstResult();
    }
}
