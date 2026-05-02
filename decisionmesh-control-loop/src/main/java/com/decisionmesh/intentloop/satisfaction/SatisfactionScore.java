package com.decisionmesh.intentloop.satisfaction;

public class SatisfactionScore {

    private final double value; // 0.0 - 1.0

    private SatisfactionScore(double value) {
        if (value < 0 || value > 1)
            throw new IllegalArgumentException("Satisfaction must be 0..1");
        this.value = value;
    }

    public static SatisfactionScore of(double value) {
        return new SatisfactionScore(value);
    }

    public double getValue() { return value; }
}