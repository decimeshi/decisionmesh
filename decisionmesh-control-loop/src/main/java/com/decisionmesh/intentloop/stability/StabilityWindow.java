package com.decisionmesh.intentloop.stability;

import java.util.LinkedList;
import java.util.Queue;

public class StabilityWindow {

    private final int windowSize;
    private final Queue<Double> scores = new LinkedList<>();

    public StabilityWindow(int windowSize) {
        this.windowSize = windowSize;
    }

    public void add(double score) {
        if (scores.size() >= windowSize) {
            scores.poll();
        }
        scores.add(score);
    }

    public boolean isStable(double threshold) {
        if (scores.size() < windowSize) return false;
        return scores.stream().allMatch(s -> s >= threshold);
    }
}