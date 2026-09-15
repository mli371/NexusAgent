package com.nexusagent.query.live;

import java.time.Instant;
import java.util.List;

import reactor.core.publisher.Mono;

interface SemanticContextCache {
    boolean enabled();
    Mono<Read> read(String scopeHash, int dimension);
    Mono<Void> put(Source source);

    record Source(int schemaVersion, String scopeHash, String questionHash, List<Float> vector,
                  SemanticReusePolicy.Features features, String cacheKey, String fingerprint,
                  Instant createdAt, Instant expiresAt) { }
    record Read(List<Source> sources, String reason) { }

    static SemanticContextCache disabled() {
        return new SemanticContextCache() {
            public boolean enabled() { return false; }
            public Mono<Read> read(String scope, int dimension) { return Mono.just(new Read(List.of(), "disabled")); }
            public Mono<Void> put(Source source) { return Mono.empty(); }
        };
    }
}
