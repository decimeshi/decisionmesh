package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.contracts.security.repository.MemberShipRepository;
import com.decisionmesh.contracts.security.repository.UserRepository;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@ApplicationScoped
public class TenantResolverService {

    @Inject
    JsonWebToken jwt;
    @Inject
    UserRepository userRepository;
    @Inject
    MemberShipRepository memberRepository;
    @Inject
    TenantContext tenantContext;
    @Inject
    SecurityIdentity securityIdentity;

    public Uni<Void> resolve(UUID projectId) {

        String externalId = jwt.getSubject();
        String role = securityIdentity.getAttribute("role");

        return userRepository.find("externalUserId", externalId).firstResult()
                .onItem().ifNull().failWith(new WebApplicationException("User not found", 401))
                .chain(user ->
                        memberRepository.findMember(user.userId, projectId)
                                .onItem().ifNull().failWith(new ForbiddenException("No access"))
                                .invoke(member -> tenantContext.setUserContext(member.tenantId, user.userId,role))
                )
                .replaceWithVoid();
    }
}
