package com.decisionmesh.cqrs.query;

import com.decisionmesh.cqrs.readmodel.IntentView;
import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface IntentQueryService {

    Uni<IntentView> findById(UUID id);

}