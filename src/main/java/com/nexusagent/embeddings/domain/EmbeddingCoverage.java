package com.nexusagent.embeddings.domain;

public record EmbeddingCoverage(int childCount, int embeddedCount, int matchingCount) {
    public int mismatchedCount() { return embeddedCount - matchingCount; }
    public int missingCount() { return childCount - embeddedCount; }
    public boolean complete() { return childCount > 0 && matchingCount == childCount; }
}
