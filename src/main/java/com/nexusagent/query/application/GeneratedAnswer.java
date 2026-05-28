package com.nexusagent.query.application;

import java.util.List;

public record GeneratedAnswer(
        String answer,
        String generatorName,
        List<String> limitations
) {

    public GeneratedAnswer {
        limitations = List.copyOf(limitations);
    }
}
