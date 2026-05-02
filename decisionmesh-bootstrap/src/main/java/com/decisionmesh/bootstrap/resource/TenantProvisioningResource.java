package com.decisionmesh.bootstrap.resource;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Kept as a no-op stub for backward compatibility.
 *
 * Provisioning is now handled exclusively by OnboardingService
 * via GET /api/onboard/me, called by the UI in AppWrapper
 * immediately after Keycloak authentication.
 *
 * This endpoint can be safely deleted once the UI no longer
 * references /api/provision.
 */
@Path("/api/provision")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class TenantProvisioningResource {

    @POST
    @Authenticated
    public Uni<Response> provision() {
        // Provisioning is handled by /api/onboard/me — nothing to do here.
        return Uni.createFrom().item(Response.noContent().build());
    }
}
