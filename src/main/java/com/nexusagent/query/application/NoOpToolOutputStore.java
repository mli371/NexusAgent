package com.nexusagent.query.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix = "nexus.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpToolOutputStore implements ToolOutputStore {

    /*
     * Milestone 6 placeholder: accepts writes but stores nothing.
     * Redis-backed intermediate output storage is intentionally deferred.
     */
    @Override
    public Mono<Void> save(String traceId, String key, Object value) {
        return Mono.empty();
    }

    @Override
    public Mono<Object> get(String traceId, String key) {
        return Mono.empty();
    }
}
