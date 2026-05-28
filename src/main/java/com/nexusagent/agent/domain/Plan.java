package com.nexusagent.agent.domain;

import java.util.List;

public record Plan(
        String plannerName,
        String rationale,
        List<PlanStep> steps
) {

    public Plan {
        steps = List.copyOf(steps);
    }

    public boolean includes(PlanAction action) {
        return steps.stream().anyMatch(step -> step.action() == action);
    }
}
