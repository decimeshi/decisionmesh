package com.decisionmesh.bootstrap.learning.exploration;

import java.util.Random;

public class EpsilonGreedyEngine {

    private final double epsilon;
    private final Random random = new Random();

    public EpsilonGreedyEngine(double epsilon) {
        this.epsilon = epsilon;
    }

    public boolean shouldExplore() {
        return random.nextDouble() < epsilon;
    }
}