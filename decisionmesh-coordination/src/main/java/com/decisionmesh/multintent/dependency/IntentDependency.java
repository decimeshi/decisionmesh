package com.decisionmesh.multintent.dependency;

import java.util.UUID;

public record IntentDependency(UUID parentIntentId, UUID childIntentId) {

}