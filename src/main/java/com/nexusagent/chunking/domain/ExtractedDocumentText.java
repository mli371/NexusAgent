package com.nexusagent.chunking.domain;

import java.util.UUID;

public record ExtractedDocumentText(
        UUID documentId,
        String text,
        String sourceContentType
) {
}
