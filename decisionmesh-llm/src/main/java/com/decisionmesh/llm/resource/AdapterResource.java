package com.decisionmesh.llm.resource;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.llm.dto.AdapterRequest;
import com.decisionmesh.llm.service.AdapterPerformanceService;
import com.decisionmesh.llm.service.AdapterService;
import com.decisionmesh.persistence.entity.AdapterEntity;
import com.decisionmesh.persistence.entity.AdapterPerformanceEntity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/adapters")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class AdapterResource {

    @Inject JsonWebToken              jwt;
    @Inject TenantContext             tenantContext;
    @Inject AdapterService            service;
    @Inject AdapterPerformanceService adapterPerformanceService;

    @GET
    public Uni<List<AdapterEntity>> list() {
        return service.list(tenantId());
    }

    @POST
    public Uni<AdapterEntity> create(AdapterRequest req) {
        return service.create(tenantId(), req);
    }

    @PUT
    @Path("/{id}")
    public Uni<AdapterEntity> update(@PathParam("id") UUID id, AdapterRequest req) {
        return service.update(tenantId(), id, req);
    }

    @PATCH
    @Path("/{id}/status")
    public Uni<AdapterEntity> toggle(@PathParam("id") UUID id, Map<String, Boolean> body) {
        Boolean isActive = body.get("isActive");
        if (isActive == null) throw new BadRequestException("Missing 'isActive' field");
        return service.toggle(tenantId(), id, isActive);
    }

    @DELETE
    @Path("/{id}")
    public Uni<Void> delete(@PathParam("id") UUID id) {
        return service.delete(tenantId(), id);
    }

    @GET
    @Path("/{id}/performance")
    public Uni<AdapterPerformanceEntity> performance(@PathParam("id") UUID id) {
        return adapterPerformanceService.get(tenantId(), id);
    }

    private UUID tenantId() {
        UUID ctxTid = tenantContext.getTenantId();
        if (ctxTid != null) return ctxTid;
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank())
            throw new ForbiddenException("Missing tenantId — onboarding not complete");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }
}