package com.nexusagent.query.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.query")
public class QueryProperties {

    private Duration contextTimeout = Duration.ofSeconds(20);
    private Duration answerTimeout = Duration.ofSeconds(5);

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
