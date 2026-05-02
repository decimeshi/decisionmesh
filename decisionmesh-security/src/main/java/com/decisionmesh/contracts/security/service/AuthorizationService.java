package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.repository.MemberShipRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class AuthorizationService {

    @Inject
    MemberShipRepository memberRepository;

    public Uni<Boolean> hasRole(UUID tenantId, UUID userId, UUID projectId, String requiredRole) {
        return memberRepository.findMember(tenantId, userId, projectId)
                .onItem().ifNotNull().transform(member -> {

                    switch (member.role) {
                        case "ADMIN":
                            return true;

                        case "ANALYST":
                            return !requiredRole.equals("ADMIN");

                        case "VIEWER":
                            return requiredRole.equals("VIEWER");

                        default:
                            return false;
                    }
                })
                .onItem().ifNull().continueWith(false);
    }
}