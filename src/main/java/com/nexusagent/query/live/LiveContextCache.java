package com.nexusagent.query.live;

import com.nexusagent.context.domain.ContextBuildResult;
import java.time.Instant;
import reactor.core.publisher.Mono;

interface LiveContextCache {
    boolean enabled();
    Mono<Lookup> get(String key);
    Mono<Void> put(String key, ContextBuildResult context);
    default Mono<Stored> putWithReceipt(String key, ContextBuildResult context) {
        return put(key, context).then(Mono.empty());
    }

    record Stored(String cacheKey, String fingerprint, Instant expiresAt) { }
    record Lookup(ContextBuildResult context, String reason, String fingerprint, Instant expiresAt) {
        Lookup(ContextBuildResult context, String reason) { this(context, reason, null, null); }
    }

    static LiveContextCache disabled() {
        return new LiveContextCache() {
            public boolean enabled() { return false; }
            public Mono<Lookup> get(String key) { return Mono.just(new Lookup(null, "disabled")); }
            public Mono<Void> put(String key, ContextBuildResult context) { return Mono.empty(); }
        };
    }
}
