package com.decisionmesh.governance.snapshot;

import com.decisionmesh.billing.model.SubscriptionEntity;
import java.time.Instant;

public class SLASnapshot {

    private final Instant windowStart;
    private final Instant windowEnd;
    private final boolean breached;

    // 🔥 NEW
    private final SubscriptionEntity.Plan plan;

    public SLASnapshot(Instant windowStart,
                       Instant windowEnd,
                       boolean breached,
                       SubscriptionEntity.Plan plan) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.breached = breached;
        this.plan = plan;
    }

    public SubscriptionEntity.Plan getPlan() {
        return plan;
    }
}