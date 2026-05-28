package com.nexusagent.agent.domain;

public record PlanStep(
        int stepIndex,
        PlanAction action,
        String description
) {
}
