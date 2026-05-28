package com.nexusagent.query.application;

import com.nexusagent.context.domain.ContextBuildResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix = "nexus.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpRetrievalCacheService implements RetrievalCacheService {

    /*
     * Milestone 6 placeholder: always miss and persist nothing.
     * Redis-backed retrieval caching is intentionally deferred.
     */
    @Override
    public Mono<ContextBuildResult> get(QueryCacheKey key) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> put(QueryCacheKey key, ContextBuildResult value) {
        return Mono.empty();
    }
}
