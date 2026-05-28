package com.nexusagent.documents.domain;

import java.util.Locale;

import com.nexusagent.common.error.BadRequestException;

public enum DocumentVisibility {
    PRIVATE,
    TENANT;

    public static DocumentVisibility fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return TENANT;
        }
        try {
            return DocumentVisibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("visibility must be PRIVATE or TENANT");
        }
    }
}
