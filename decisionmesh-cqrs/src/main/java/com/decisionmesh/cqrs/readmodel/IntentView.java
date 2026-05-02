package com.decisionmesh.cqrs.readmodel;

import java.util.UUID;

public record IntentView(UUID id, String tenantId, String phase, String satisfaction, long version) {

}