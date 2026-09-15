package com.nexusagent.query.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.query")
public class QueryProperties {

    private Duration contextTimeout = Duration.ofSeconds(20);
    private Duration answerTimeout = Duration.ofSeconds(5);
    private String answerProvider = "local";
    private Duration liveContextTimeout = Duration.ofSeconds(45);
    private Duration stateTimeout = Duration.ofSeconds(2);
    private boolean liveCacheEnabled = true;
    private Duration liveCacheTtl = Duration.ofMinutes(15);
    private Duration liveCacheTimeout = Duration.ofMillis(500);
    private int liveCacheMaxBytes = 131072;

    public boolean isLiveCacheEnabled() { return liveCacheEnabled; }
    public void setLiveCacheEnabled(boolean enabled) { this.liveCacheEnabled = enabled; }
    public Duration getLiveCacheTtl() { return liveCacheTtl; }
    public void setLiveCacheTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Live cache TTL must be positive and at most 1 hour");
        }
        this.liveCacheTtl = ttl;
    }
    public Duration getLiveCacheTimeout() { return liveCacheTimeout; }
    public void setLiveCacheTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException("Live cache timeout must be positive and at most 2 seconds");
        }
        this.liveCacheTimeout = timeout;
    }
    public int getLiveCacheMaxBytes() { return liveCacheMaxBytes; }
    public void setLiveCacheMaxBytes(int bytes) {
        if (bytes < 1 || bytes > 1048576) { throw new IllegalArgumentException("Live cache size must be 1 to 1048576 bytes"); }
        this.liveCacheMaxBytes = bytes;
    }

    public String getAnswerProvider() { return answerProvider; }
    public void setAnswerProvider(String answerProvider) { this.answerProvider = answerProvider; }
    public Duration getLiveContextTimeout() { return liveContextTimeout; }
    public void setLiveContextTimeout(Duration timeout) { this.liveContextTimeout = timeout; }
    public Duration getStateTimeout() { return stateTimeout; }
    public void setStateTimeout(Duration timeout) { this.stateTimeout = timeout; }

    public Duration getContextTimeout() {
        return contextTimeout;
    }

    public void setContextTimeout(Duration contextTimeout) {
        this.contextTimeout = contextTimeout;
    }

    public Duration getAnswerTimeout() {
        return answerTimeout;
    }

    public void setAnswerTimeout(Duration answerTimeout) {
        this.answerTimeout = answerTimeout;
    }
}
