package com.decisionmesh.bootstrap.service;

import com.decisionmesh.persistence.entity.InvitationEntity;
import com.decisionmesh.persistence.repository.InvitationRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class InvitationService {

    @Inject
    InvitationRepository repository;
    @Inject EmailService emailService;

    public Uni<InvitationEntity> createInvitation(UUID tenantId, String email, String role) {

        return Panache.withTransaction(() -> {

            InvitationEntity inv = new InvitationEntity();

            inv.tenantId = tenantId;
            inv.email = email;
            inv.role = role;
            inv.status = "PENDING";
            inv.token = UUID.randomUUID().toString();
            inv.createdAt = OffsetDateTime.now();
            inv.expiresAt = OffsetDateTime.now().plusDays(7);

            return repository.persist(inv)
                    .call(saved -> {
                        String link = "http://localhost:3000/invite/" + saved.token;
                        return emailService.sendInviteEmail(email, link);
                    });
        });
    }

    public Uni<List<InvitationEntity>> list(UUID tenantId) {
        return Panache.withSession(() ->
                repository.findByTenant(tenantId)
        );
    }

    public Uni<Void> revoke(UUID id) {
        return Panache.withTransaction(() ->
                repository.deleteById(id).replaceWithVoid()
        );
    }
}
