package com.decisionmesh.llm.selector;

import java.util.UUID;

/**
 * Result of LlmModelSelector.select() — carries the chosen adapter
 * plus metadata about why it was chosen (for audit and learning).
 */
public record SelectedAdapter(
        UUID    adapterId,
        String  provider,
        String  model,
        String  region,
        double  compositeScore,
        int     rank,
        boolean wasExploration,   // true if chosen via epsilon-greedy exploration
        boolean isColdStart,      // true if adapter has no prior execution history
        String  selectionReason   // human-readable explanation
) {
    public static SelectedAdapter of(AdapterStats stats, int rank,
                                      boolean wasExploration, String reason) {
        return new SelectedAdapter(
                stats.getAdapterId(),
                stats.getProvider(),
                stats.getModel(),
                stats.getRegion(),
                stats.compositeScore(),
                rank,
                wasExploration,
                stats.isColdStart(),
                reason
        );
    }
}
