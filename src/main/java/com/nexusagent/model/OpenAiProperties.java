package com.nexusagent.model;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.openai")
public class OpenAiProperties {
    private String apiKey = "";
    private String answerModel = "gpt-5.6-luna";
    private Duration embeddingTimeout = Duration.ofSeconds(20);
    private Duration answerTimeout = Duration.ofSeconds(90);

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getAnswerModel() { return answerModel; }
    public void setAnswerModel(String answerModel) { this.answerModel = answerModel; }
    public Duration getEmbeddingTimeout() { return embeddingTimeout; }
    public void setEmbeddingTimeout(Duration timeout) { this.embeddingTimeout = timeout; }
    public Duration getAnswerTimeout() { return answerTimeout; }
    public void setAnswerTimeout(Duration timeout) { this.answerTimeout = timeout; }

    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI requires NEXUS_LLM_API_KEY; no local fallback is enabled");
        }
        if (apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("NEXUS_LLM_API_KEY must not contain whitespace");
        }
        if (answerModel == null || answerModel.isBlank()) {
            throw new IllegalStateException("NEXUS_LLM_MODEL must not be blank for OpenAI");
        }
        requireTimeout(embeddingTimeout);
        requireTimeout(answerTimeout);
    }

    private void requireTimeout(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("OpenAI timeouts must be positive and at most 5 minutes");
        }
    }
}
