package com.nexusagent.storage;

import java.util.UUID;

import com.nexusagent.common.error.BadRequestException;
import org.springframework.stereotype.Component;

@Component
public class ObjectKeyFactory {

    public String documentObjectKey(UUID documentId, String originalFilename) {
        return "documents/%s/%s".formatted(documentId, sanitizeFilename(originalFilename));
    }

    public String sanitizeFilename(String filename) {
        if (filename == null) {
            throw new BadRequestException("Uploaded file must have a filename");
        }

        String normalized = filename.replace('\\', '/').trim();
        int lastSlash = normalized.lastIndexOf('/');
        String baseName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        String cleaned = baseName.replaceAll("[^A-Za-z0-9._-]", "_");

        if (cleaned.isBlank()) {
            throw new BadRequestException("Uploaded file must have a filename");
        }

        return cleaned;
    }
}
