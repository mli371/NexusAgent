package com.nexusagent.documents.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentStatus;
import com.nexusagent.documents.domain.DocumentVisibility;

public record DocumentResponse(
        UUID id,
        String tenantId,
        String ownerId,
        DocumentVisibility visibility,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        String minioBucket,
        String minioObjectKey,
        DocumentStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static DocumentResponse from(DocumentMetadata metadata) {
        return new DocumentResponse(
                metadata.id(),
                metadata.tenantId(),
                metadata.ownerId(),
                metadata.visibility(),
                metadata.originalFilename(),
                metadata.contentType(),
                metadata.sizeBytes(),
                metadata.sha256(),
                metadata.minioBucket(),
                metadata.minioObjectKey(),
                metadata.status(),
                metadata.createdAt(),
                metadata.updatedAt()
        );
    }
}
