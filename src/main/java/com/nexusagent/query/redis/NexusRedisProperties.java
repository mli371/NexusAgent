package com.nexusagent.query.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.redis")
public class NexusRedisProperties {

    private boolean enabled = true;
    private Duration recentSessionTtl = Duration.ofHours(24);
    private Duration sessionSummaryTtl = Duration.ofDays(7);
    private Duration retrievalCacheTtl = Duration.ofMinutes(30);
    private Duration toolOutputTtl = Duration.ofHours(2);
    private Duration queryStatusTtl = Duration.ofMinutes(30);
    private int maxRecentSessionEvents = 25;
    private int maxCacheEntryBytes = 131_072;
    private int maxToolOutputBytes = 65_536;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRecentSessionTtl() {
        return recentSessionTtl;
    }

    public void setRecentSessionTtl(Duration recentSessionTtl) {
        this.recentSessionTtl = requirePositiveDuration(recentSessionTtl, "recentSessionTtl");
    }

    public Duration getSessionSummaryTtl() {
        return sessionSummaryTtl;
    }

    public void setSessionSummaryTtl(Duration sessionSummaryTtl) {
        this.sessionSummaryTtl = requirePositiveDuration(sessionSummaryTtl, "sessionSummaryTtl");
    }

    public Duration getRetrievalCacheTtl() {
        return retrievalCacheTtl;
    }

    public void setRetrievalCacheTtl(Duration retrievalCacheTtl) {
        this.retrievalCacheTtl = requirePositiveDuration(retrievalCacheTtl, "retrievalCacheTtl");
    }

    public Duration getToolOutputTtl() {
        return toolOutputTtl;
    }

    public void setToolOutputTtl(Duration toolOutputTtl) {
        this.toolOutputTtl = requirePositiveDuration(toolOutputTtl, "toolOutputTtl");
    }

    public Duration getQueryStatusTtl() {
        return queryStatusTtl;
    }

    public void setQueryStatusTtl(Duration queryStatusTtl) {
        this.queryStatusTtl = requirePositiveDuration(queryStatusTtl, "queryStatusTtl");
    }

    public int getMaxRecentSessionEvents() {
        return maxRecentSessionEvents;
    }

    public void setMaxRecentSessionEvents(int maxRecentSessionEvents) {
        this.maxRecentSessionEvents = requirePositiveInt(maxRecentSessionEvents, "maxRecentSessionEvents");
    }

    public int getMaxCacheEntryBytes() {
        return maxCacheEntryBytes;
    }

    public void setMaxCacheEntryBytes(int maxCacheEntryBytes) {
        this.maxCacheEntryBytes = requirePositiveInt(maxCacheEntryBytes, "maxCacheEntryBytes");
    }

    public int getMaxToolOutputBytes() {
        return maxToolOutputBytes;
    }

    public void setMaxToolOutputBytes(int maxToolOutputBytes) {
        this.maxToolOutputBytes = requirePositiveInt(maxToolOutputBytes, "maxToolOutputBytes");
    }

    private Duration requirePositiveDuration(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be a positive duration");
        }
        return value;
    }

    private int requirePositiveInt(int value, String propertyName) {
        if (value < 1) {
            throw new IllegalArgumentException(propertyName + " must be greater than 0");
        }
        return value;
    }
}
