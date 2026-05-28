package com.nexusagent.query.redis;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.query.application.SessionStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix = "nexus.redis", name = "enabled", havingValue = "true")
public class RedisSessionStateService implements SessionStateService {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStateService.class);
    private static final int SCHEMA_VERSION = 1;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NexusRedisProperties redisProperties;
    private final RedisKeyFactory keyFactory;

    public RedisSessionStateService(
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NexusRedisProperties redisProperties,
            RedisKeyFactory keyFactory
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisProperties = redisProperties;
        this.keyFactory = keyFactory;
    }

    @Override
    public Mono<Void> recordStarted(String sessionId, String traceId, String question) {
        return record(sessionId, traceId, "started", question);
    }

    @Override
    public Mono<Void> recordCompleted(String sessionId, String traceId) {
        return record(sessionId, traceId, "completed", null);
    }

    @Override
    public Mono<Void> recordFailed(String sessionId, String traceId, String errorMessage) {
        return record(sessionId, traceId, "failed", errorMessage);
    }

    public Mono<List<SessionEventEnvelope>> recentEvents(String sessionId) {
        if (isBlank(sessionId)) {
            return Mono.just(List.of());
        }
        String key = keyFactory.sessionRecent(sessionId);
        return redisTemplate.opsForList()
                .range(key, 0, -1)
                .flatMap(this::deserializeSessionEvent)
                .collectList()
                .onErrorResume(error -> {
                    log.warn("Redis session recent read failed for key {}: {}", key, error.getMessage());
                    return Mono.just(List.of());
                });
    }

    public Mono<SessionSummaryEnvelope> summary(String sessionId) {
        if (isBlank(sessionId)) {
            return Mono.empty();
        }
        String key = keyFactory.sessionSummary(sessionId);
        return redisTemplate.opsForValue()
                .get(key)
                .flatMap(this::deserializeSessionSummary)
                .onErrorResume(error -> {
                    log.warn("Redis session summary read failed for key {}: {}", key, error.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<QueryStatusEnvelope> queryStatus(String traceId) {
        if (isBlank(traceId)) {
            return Mono.empty();
        }
        String key = keyFactory.queryStatus(traceId);
        return redisTemplate.opsForValue()
                .get(key)
                .flatMap(this::deserializeQueryStatus)
                .onErrorResume(error -> {
                    log.warn("Redis query status read failed for key {}: {}", key, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> record(String sessionId, String traceId, String status, String detail) {
        Instant now = Instant.now();
        SessionEventEnvelope event = new SessionEventEnvelope(
                SCHEMA_VERSION,
                traceId,
                status,
                detail,
                now
        );
        QueryStatusEnvelope queryStatus = new QueryStatusEnvelope(
                SCHEMA_VERSION,
                traceId,
                status,
                now
        );

        Mono<Void> queryStatusWrite = writeQueryStatus(traceId, queryStatus);
        if (isBlank(sessionId)) {
            return queryStatusWrite;
        }

        SessionSummaryEnvelope summary = new SessionSummaryEnvelope(
                SCHEMA_VERSION,
                sessionId,
                traceId,
                status,
                now
        );
        return Mono.whenDelayError(
                        writeRecentEvent(sessionId, event),
                        writeSummary(sessionId, summary),
                        queryStatusWrite
                )
                .onErrorResume(error -> {
                    log.warn("Redis session state write failed for session {}: {}", sessionId, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> writeRecentEvent(String sessionId, SessionEventEnvelope event) {
        String key = keyFactory.sessionRecent(sessionId);
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
                .flatMap(json -> redisTemplate.opsForList().leftPush(key, json))
                .then(redisTemplate.opsForList().trim(key, 0, redisProperties.getMaxRecentSessionEvents() - 1))
                .then(redisTemplate.expire(key, redisProperties.getRecentSessionTtl()))
                .then();
    }

    private Mono<Void> writeSummary(String sessionId, SessionSummaryEnvelope summary) {
        String key = keyFactory.sessionSummary(sessionId);
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(summary))
                .flatMap(json -> redisTemplate.opsForValue().set(key, json, redisProperties.getSessionSummaryTtl()))
                .then();
    }

    private Mono<Void> writeQueryStatus(String traceId, QueryStatusEnvelope queryStatus) {
        if (isBlank(traceId)) {
            return Mono.empty();
        }
        String key = keyFactory.queryStatus(traceId);
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(queryStatus))
                .flatMap(json -> redisTemplate.opsForValue().set(key, json, redisProperties.getQueryStatusTtl()))
                .then()
                .onErrorResume(error -> {
                    log.warn("Redis query status write failed for key {}: {}", key, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<SessionEventEnvelope> deserializeSessionEvent(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, SessionEventEnvelope.class));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }

    private Mono<SessionSummaryEnvelope> deserializeSessionSummary(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, SessionSummaryEnvelope.class));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }

    private Mono<QueryStatusEnvelope> deserializeQueryStatus(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, QueryStatusEnvelope.class));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SessionEventEnvelope(
            int schemaVersion,
            String traceId,
            String status,
            String detail,
            Instant timestamp
    ) {
    }

    public record SessionSummaryEnvelope(
            int schemaVersion,
            String sessionId,
            String lastTraceId,
            String lastStatus,
            Instant lastUpdatedAt
    ) {
    }

    public record QueryStatusEnvelope(
            int schemaVersion,
            String traceId,
            String status,
            Instant updatedAt
    ) {
    }
}
