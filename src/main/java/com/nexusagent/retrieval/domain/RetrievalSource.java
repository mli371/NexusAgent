package com.nexusagent.retrieval.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RetrievalSource {
    VECTOR("vector"),
    FULL_TEXT("full_text"),
    BOTH("both");

    private final String value;

    RetrievalSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
