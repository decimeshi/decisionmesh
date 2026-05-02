package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.ProjectEntity;
import com.decisionmesh.contracts.security.entity.MemberShipEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {

    private static final Logger LOG = Logger.getLogger(ProjectService.class);

    /**
     * Provisions the default workspace and assigns the user as ADMIN.
     * Transactional to ensure we don't have projects without members.
     */
    public Uni<Void> initializeDefaultProject(UUID tenantId, UUID userId) {
        return Panache.withTransaction(() -> {
            ProjectEntity project = new ProjectEntity();
            project.tenantId = tenantId;
            project.name = "Default Workspace";
            project.description = "Primary environment for decision governance.";
            project.environment = "Production";
            project.isDefault = true;

            return project.<ProjectEntity>persist()
                    .flatMap(savedProject -> {
                        MemberShipEntity member = new MemberShipEntity();
                        member.tenantId = tenantId;
                        member.userId = userId;
                        member.projectId = savedProject.id;
                        member.role = "ADMIN"; // First user gets full control

                        LOG.infof("Initializing default project %s for tenant %s", savedProject.id, tenantId);
                        return member.persist();
                    })
                    .replaceWithVoid();
        });
    }

    public Uni<ProjectEntity> findById(UUID projectId) {
        return ProjectEntity.findById(projectId);
    }
}
