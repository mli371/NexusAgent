package com.nexusagent.documents.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentMetadata(
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

    public static DocumentMetadata stored(
            UUID id,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String sha256,
            String minioBucket,
            String minioObjectKey,
            OffsetDateTime timestamp
    ) {
        return stored(
                id,
                "default",
                "anonymous",
                DocumentVisibility.TENANT,
                originalFilename,
                contentType,
                sizeBytes,
                sha256,
                minioBucket,
                minioObjectKey,
                timestamp
        );
    }

    public static DocumentMetadata stored(
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
            OffsetDateTime timestamp
    ) {
        return new DocumentMetadata(
                id,
                tenantId,
                ownerId,
                visibility,
                originalFilename,
                contentType,
                sizeBytes,
                sha256,
                minioBucket,
                minioObjectKey,
                DocumentStatus.STORED,
                timestamp,
                timestamp
        );
    }
}
