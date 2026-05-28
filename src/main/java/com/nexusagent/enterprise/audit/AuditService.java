package com.nexusagent.enterprise.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_METADATA_STRING_CHARS = 256;
    private static final int MAX_METADATA_ITEMS = 25;
    private static final int MAX_METADATA_DEPTH = 3;
    private static final int MAX_METADATA_JSON_CHARS = 4096;

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditEventRepository auditEventRepository, ObjectMapper objectMapper, Clock clock) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Mono<Void> record(
            RequestContext context,
            String traceId,
            AuditEventType eventType,
            String resourceType,
            UUID resourceId,
            UUID documentId,
            Map<String, Object> metadata
    ) {
        RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
        AuditEvent event = new AuditEvent(
                UUID.randomUUID(),
                effectiveContext.tenantId(),
                effectiveContext.actorId(),
                safeTraceId(traceId),
                eventType,
                resourceType,
                resourceId,
                documentId,
                toJson(metadata),
                OffsetDateTime.now(clock)
        );
        return auditEventRepository.save(event)
                .doOnSuccess(saved -> log.info(
                        "audit_event_created eventType={} tenantId={} actorId={} traceId={} resourceType={} resourceId={} documentId={}",
                        saved.eventType(),
                        saved.tenantId(),
                        saved.actorId(),
                        saved.traceId(),
                        saved.resourceType(),
                        saved.resourceId(),
                        saved.documentId()
                ))
                .onErrorResume(error -> {
                    log.warn(
                            "audit_event_write_failed eventType={} tenantId={} traceId={} reason={}",
                            eventType,
                            effectiveContext.tenantId(),
                            safeTraceId(traceId),
                            error.getMessage()
                    );
                    return Mono.empty();
                })
                .then();
    }

    public String hashSensitiveValue(String value) {
        if (value == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String safeTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return "not-set";
        }
        return traceId.trim();
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            String json = objectMapper.writeValueAsString(sanitizeMetadata(metadata));
            if (json.length() <= MAX_METADATA_JSON_CHARS) {
                return json;
            }
            return objectMapper.writeValueAsString(Map.of(
                    "metadataTooLarge", true,
                    "retained", false
            ));
        } catch (JsonProcessingException exception) {
            log.warn("audit_metadata_serialization_failed reason={}", exception.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.entrySet().stream()
                .limit(MAX_METADATA_ITEMS)
                .forEach(entry -> {
                    String key = sanitizeKey(entry.getKey());
                    if (key.isBlank() || isSensitiveMetadataKey(key)) {
                        return;
                    }
                    sanitized.put(key, sanitizeValue(entry.getValue(), 0));
                });
        return sanitized;
    }

    private String sanitizeKey(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.trim();
        if (trimmed.length() <= MAX_METADATA_STRING_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_METADATA_STRING_CHARS);
    }

    private boolean isSensitiveMetadataKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("rawdocument")
                || normalized.contains("documenttext")
                || normalized.contains("chunktext")
                || normalized.contains("fullcontext")
                || normalized.contains("finalcontext")
                || normalized.contains("contexttext")
                || normalized.contains("embedding")
                || normalized.contains("vector");
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof UUID) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof CharSequence charSequence) {
            return truncate(charSequence.toString());
        }
        if (depth >= MAX_METADATA_DEPTH) {
            return truncate(String.valueOf(value));
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> nested = new LinkedHashMap<>();
            mapValue.entrySet().stream()
                    .limit(MAX_METADATA_ITEMS)
                    .forEach(entry -> {
                        String key = sanitizeKey(String.valueOf(entry.getKey()));
                        if (!key.isBlank() && !isSensitiveMetadataKey(key)) {
                            nested.put(key, sanitizeValue(entry.getValue(), depth + 1));
                        }
                    });
            return nested;
        }
        if (value instanceof Collection<?> collectionValue) {
            List<Object> items = new ArrayList<>();
            collectionValue.stream()
                    .limit(MAX_METADATA_ITEMS)
                    .forEach(item -> items.add(sanitizeValue(item, depth + 1)));
            return items;
        }
        return truncate(String.valueOf(value));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_METADATA_STRING_CHARS) {
            return value;
        }
        return value.substring(0, MAX_METADATA_STRING_CHARS) + "...";
    }
}
