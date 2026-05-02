package com.decisionmesh.contracts.security.context;

import io.quarkus.security.ForbiddenException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@RequestScoped
public class TenantContext {
    private UUID tenantId;
    private UUID userId;
    private String authType;
    private boolean apiKeyId;
    private String role;

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isApiKey() {
        return apiKeyId ;
    }


    public void setUserContext(UUID tenantId, UUID userId, String role) {
        if (this.tenantId != null) return;
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
        this.authType = "JWT";
    }
}
