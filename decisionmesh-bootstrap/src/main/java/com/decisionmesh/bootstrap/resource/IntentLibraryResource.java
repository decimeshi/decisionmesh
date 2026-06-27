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

    @GET
    @Path("/{vertical}")
    @WithSession
    public Uni<List<IntentLibraryEntity>> byVertical(
            @PathParam("vertical") String vertical) {
        return service.getByVertical(vertical.toUpperCase());
    }

    @GET
    @Path("/{vertical}/meta/categories")
    @WithSession
    public Uni<List<CategoryResponse>> categories(
            @PathParam("vertical") String vertical) {
        return service.getCategorySummaries(vertical.toUpperCase());
    }

    @GET
    @Path("/{vertical}/by-category/{category}")
    @WithSession
    public Uni<List<IntentLibraryEntity>> byCategory(
            @PathParam("vertical") String vertical,
            @PathParam("category") String category) {
        return service.getByVerticalAndCategory(
                vertical.toUpperCase(), category.toUpperCase());
    }

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

    @GET
    @Path("/intent/{id}")
    @WithSession
    public Uni<IntentLibraryEntity> getById(@PathParam("id") UUID id) {
        return service.getById(id)
                .onItem().ifNull()
                .failWith(new NotFoundException("Intent not found: " + id));
    }

    // GET /api/intent-library/by-name/{name}
    // Used by Playground.jsx setIntentType() to load full examplePayload
    // when user selects an intent type from the chip browser.
    @GET
    @Path("/by-name/{name}")
    @WithSession
    public Uni<IntentLibraryEntity> byName(@PathParam("name") String name) {
        return service.getByName(name)
                .onItem().ifNull()
                .failWith(new NotFoundException("Intent not found: " + name));
    }
}