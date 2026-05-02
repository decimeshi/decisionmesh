package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.dto.CategoryResponse;
import com.decisionmesh.bootstrap.service.IntentLibraryService;
import com.decisionmesh.persistence.entity.IntentLibraryEntity;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/intent-library")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class IntentLibraryResource {

    @Inject
    IntentLibraryService service;

    // GET /api/intent-library/fintech
    // → FintechIntents.jsx: request(keycloak, `/intent-library/${vertical.toLowerCase()}`)
    @GET
    @Path("/{vertical}")
    @WithSession
    public Uni<List<IntentLibraryEntity>> byVertical(
            @PathParam("vertical") String vertical) {
        return service.getByVertical(vertical.toUpperCase());
    }

    // GET /api/intent-library/fintech/meta/categories
    // → FintechIntents.jsx: request(keycloak, `/intent-library/${vertical.toLowerCase()}/meta/categories`)
    @GET
    @Path("/{vertical}/meta/categories")
    @WithSession
    public Uni<List<CategoryResponse>> categories(
            @PathParam("vertical") String vertical) {
        return service.getCategorySummaries(vertical.toUpperCase());
    }

    // GET /api/intent-library/fintech/by-category/PAYMENTS
    // → FintechIntents.jsx: request(keycloak, `/intent-library/${vertical.toLowerCase()}/by-category/${selected}`)
    @GET
    @Path("/{vertical}/by-category/{category}")
    @WithSession
    public Uni<List<IntentLibraryEntity>> byCategory(
            @PathParam("vertical") String vertical,
            @PathParam("category") String category) {
        return service.getByVerticalAndCategory(
                vertical.toUpperCase(), category.toUpperCase());
    }

    // GET /api/intent-library/fintech/search?q=fraud&tag=rbi&risk=HIGH
    // → FintechIntents.jsx: request(keycloak, `/intent-library/${vertical.toLowerCase()}/search?${qs}`)
    @GET
    @Path("/{vertical}/search")
    @WithSession
    public Uni<List<IntentLibraryEntity>> search(
            @PathParam("vertical") String vertical,
            @QueryParam("q")    String query,
            @QueryParam("tag")  String tag,
            @QueryParam("risk") String risk) {
        return service.search(vertical.toUpperCase(), query, tag, risk);
    }

    // GET /api/intent-library/intent/{id}
    // → FintechIntents.jsx: request(keycloak, `/intent-library/intent/${id}`)
    @GET
    @Path("/intent/{id}")
    @WithSession
    public Uni<IntentLibraryEntity> getById(@PathParam("id") UUID id) {
        return service.getById(id)
                .onItem().ifNull()
                .failWith(new NotFoundException("Intent not found: " + id));
    }
}