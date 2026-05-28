package com.nexusagent.query.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix = "nexus.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemorySessionStateService implements SessionStateService {

    /*
     * Milestone 6 intentionally keeps session state process-local.
     * This is not Redis-backed and is replaced in the Redis milestone.
     */
    private final Map<String, List<SessionEvent>> eventsBySessionId = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> recordStarted(String sessionId, String traceId, String question) {
        return record(sessionId, new SessionEvent(traceId, "started", question, Instant.now()));
    }

    @Override
    public Mono<Void> recordCompleted(String sessionId, String traceId) {
        return record(sessionId, new SessionEvent(traceId, "completed", null, Instant.now()));
    }

    @Override
    public Mono<Void> recordFailed(String sessionId, String traceId, String errorMessage) {
        return record(sessionId, new SessionEvent(traceId, "failed", errorMessage, Instant.now()));
    }

    public Mono<List<SessionEvent>> eventsForSession(String sessionId) {
        return Mono.fromSupplier(() -> List.copyOf(eventsBySessionId.getOrDefault(sessionId, List.of())));
    }

    private Mono<Void> record(String sessionId, SessionEvent event) {
        return Mono.fromRunnable(() -> {
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            eventsBySessionId.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        });
    }

    public record SessionEvent(
            String traceId,
            String status,
            String detail,
            Instant timestamp
    ) {
    }
}
