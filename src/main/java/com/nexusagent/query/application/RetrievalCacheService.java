package com.nexusagent.query.application;

import com.nexusagent.context.domain.ContextBuildResult;
import reactor.core.publisher.Mono;

public interface RetrievalCacheService {

    Mono<ContextBuildResult> get(QueryCacheKey key);

    Mono<Void> put(QueryCacheKey key, ContextBuildResult value);
}
