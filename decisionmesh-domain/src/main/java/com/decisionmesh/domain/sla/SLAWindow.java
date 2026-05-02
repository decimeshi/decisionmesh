package com.decisionmesh.domain.sla;

import java.time.Instant;

public final class SLAWindow {

    private final Instant start;
    private final Instant end;

    public SLAWindow(Instant start, Instant end) {
        if (end.isBefore(start)) throw new IllegalArgumentException();
        this.start = start;
        this.end = end;
    }

    public boolean isBreached(Instant now) {
        return now.isAfter(end);
    }
}