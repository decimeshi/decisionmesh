package com.decisionmesh.streaming.outbox;

import io.smallrye.mutiny.Uni;
import java.util.List;

public interface OutboxRepository {

    Uni<List<OutboxEvent>> findUnpublished();

    Uni<Void> markPublished(OutboxEvent event);

}