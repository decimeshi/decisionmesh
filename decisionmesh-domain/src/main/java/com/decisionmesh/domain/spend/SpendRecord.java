package com.decisionmesh.domain.spend;

import java.time.Instant;

public final class SpendRecord {

    private final double amount;
    private final String currency;
    private final Instant timestamp;

    public SpendRecord(double amount, String currency) {
        if (amount < 0) throw new IllegalArgumentException();
        this.amount = amount;
        this.currency = currency;
        this.timestamp = Instant.now();
    }
}