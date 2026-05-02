package com.decisionmesh.domain.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

public final class DriftScore {

    private final double value;

    @JsonCreator
    private DriftScore(@JsonProperty("value") double value) {
        if (value < 0.0)
            throw new IllegalArgumentException("Drift score must be >= 0");

        this.value = value;
    }

    public static DriftScore of(double value) {
        return new DriftScore(value);
    }

    @JsonValue
    public double value() {
        return value;
    }
}