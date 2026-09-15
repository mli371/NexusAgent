package com.nexusagent.agentrun.application;

import java.time.Duration;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.agent")
public class AgentRunProperties {
    private boolean enabled;
    private String workerMode = "scripted";
    private String workerToken = "";
    private String provider = "";
    private String model = "";
    private Duration lease = Duration.ofSeconds(45);
    private Duration activeTimeout = Duration.ofSeconds(180);
    private Duration toolTimeout = Duration.ofSeconds(10);
    private int maxTools = 30;
    private int maxRounds = 12;
    private int maxEvents = 500;
    private Duration approvalTtl = Duration.ofMinutes(30);
    private int maxRecoveries = 3;
    private Duration eventPollInterval = Duration.ofSeconds(1);

    @PostConstruct
    void validate() {
        if (!enabled) { return; }
        if (!workerMode.equals("scripted") && !workerMode.equals("pi")) {
            throw new IllegalArgumentException("nexus.agent.worker-mode must be scripted or pi");
        }
        if (workerToken.length() < 32 || workerToken.length() > 512) {
            throw new IllegalArgumentException("NEXUS_AGENT_WORKER_TOKEN must contain 32 to 512 characters");
        }
        if (workerMode.equals("pi") && (provider.isBlank() || model.isBlank())) {
            throw new IllegalArgumentException("Pi mode requires NEXUS_LLM_PROVIDER and NEXUS_LLM_MODEL");
        }
        if (lease.compareTo(Duration.ofSeconds(1)) < 0 || activeTimeout.compareTo(lease) < 0
                || toolTimeout.isNegative() || toolTimeout.isZero() || toolTimeout.compareTo(lease) >= 0
                || maxTools < 2 || maxTools > 100
                || maxRounds < 1 || maxRounds > 50 || maxEvents < maxTools * 2 + 4 || maxEvents > 1000
                || approvalTtl.isNegative() || approvalTtl.isZero() || approvalTtl.compareTo(Duration.ofHours(24)) > 0
                || maxRecoveries < 0 || maxRecoveries > 10
                || eventPollInterval.compareTo(Duration.ofMillis(100)) < 0 || eventPollInterval.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid agent execution budgets");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWorkerMode() { return workerMode; }
    public void setWorkerMode(String workerMode) { this.workerMode = workerMode; }
    public String getWorkerToken() { return workerToken; }
    public void setWorkerToken(String workerToken) { this.workerToken = workerToken; }
    public String getProvider() { return workerMode.equals("scripted") ? "scripted" : provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return workerMode.equals("scripted") ? "fixtures-v1" : model; }
    public void setModel(String model) { this.model = model; }
    public Duration getLease() { return lease; }
    public void setLease(Duration lease) { this.lease = lease; }
    public Duration getActiveTimeout() { return activeTimeout; }
    public void setActiveTimeout(Duration activeTimeout) { this.activeTimeout = activeTimeout; }
    public Duration getToolTimeout() { return toolTimeout; }
    public void setToolTimeout(Duration toolTimeout) { this.toolTimeout = toolTimeout; }
    public int getMaxTools() { return maxTools; }
    public void setMaxTools(int maxTools) { this.maxTools = maxTools; }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public int getMaxEvents() { return maxEvents; }
    public void setMaxEvents(int maxEvents) { this.maxEvents = maxEvents; }
    public Duration getApprovalTtl() { return approvalTtl; }
    public void setApprovalTtl(Duration approvalTtl) { this.approvalTtl = approvalTtl; }
    public int getMaxRecoveries() { return maxRecoveries; }
    public void setMaxRecoveries(int maxRecoveries) { this.maxRecoveries = maxRecoveries; }
    public Duration getEventPollInterval() { return eventPollInterval; }
    public void setEventPollInterval(Duration interval) { this.eventPollInterval = interval; }
}
