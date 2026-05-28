package com.nexusagent.agent.domain;

import java.util.List;

public record CritiqueResult(
        CritiqueOutcome outcome,
        boolean grounded,
        List<String> findings
) {

    public CritiqueResult {
        findings = List.copyOf(findings);
    }
}
