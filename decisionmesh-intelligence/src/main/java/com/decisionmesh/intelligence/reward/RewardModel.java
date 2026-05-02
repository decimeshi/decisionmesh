package com.decisionmesh.intelligence.reward;

public class RewardModel {

    public double compute(double latency,
                          double cost,
                          boolean success) {

        double reward = 0.0;
        if (success) reward += 1.0;
        reward -= latency * 0.1;
        reward -= cost * 0.1;
        return reward;
    }
}