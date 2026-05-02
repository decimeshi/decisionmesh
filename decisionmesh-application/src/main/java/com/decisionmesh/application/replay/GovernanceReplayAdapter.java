package com.decisionmesh.application.replay;

import com.decisionmesh.common.dto.ReplayResponse;
import com.decisionmesh.cqrs.replay.ReplayService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GovernanceReplayAdapter {

    @Inject
    ReplayService replayService;

    public Uni<List<ReplayResponse>> replay(UUID intentId) {
        return replayService.replay(intentId);
    }
}