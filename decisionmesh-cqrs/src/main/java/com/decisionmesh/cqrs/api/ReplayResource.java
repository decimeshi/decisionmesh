package com.decisionmesh.cqrs.api;


import com.decisionmesh.common.dto.ReplayResponse;
import com.decisionmesh.cqrs.replay.ReplayService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/governance/replay")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ReplayResource {

    @Inject
    ReplayService replayService;

    @GET
    @Path("/{intentId}")
    public Uni<List<ReplayResponse>> replay(@PathParam("intentId") UUID intentId) {
        return replayService.replay(intentId);
    }
}