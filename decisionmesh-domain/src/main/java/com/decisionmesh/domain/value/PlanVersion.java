package com.decisionmesh.domain.value;

public record PlanVersion(int value) {

    public PlanVersion {
        if (value <= 0)
            throw new IllegalArgumentException("Plan version must be positive");
    }

    public static PlanVersion of(int value) {
        return new PlanVersion(value);
    }

    public static PlanVersion initial() {
        return new PlanVersion(1);
    }

    public PlanVersion next() {
        return new PlanVersion(this.value + 1);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
