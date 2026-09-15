package com.nexusagent.query.application;

import java.util.List;

public record GeneratedAnswer(
        String answer,
        String generatorName,
        List<String> limitations,
        String status,
        List<String> usedCitationMarkers
) {

    public GeneratedAnswer {
        limitations = List.copyOf(limitations);
        usedCitationMarkers = List.copyOf(usedCitationMarkers);
    }

    public GeneratedAnswer(String answer, String generatorName, List<String> limitations) {
        this(answer, generatorName, limitations, "local_placeholder", List.of());
    }
}
