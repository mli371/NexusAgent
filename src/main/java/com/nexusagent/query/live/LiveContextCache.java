package com.nexusagent.query.live;

import com.nexusagent.context.domain.ContextBuildResult;
import reactor.core.publisher.Mono;

interface LiveContextCache {
    boolean enabled();
    Mono<Lookup> get(String key);
    Mono<Void> put(String key, ContextBuildResult context);

    record Lookup(ContextBuildResult context, String reason) { }

    static LiveContextCache disabled() {
        return new LiveContextCache() {
            public boolean enabled() { return false; }
            public Mono<Lookup> get(String key) { return Mono.just(new Lookup(null, "disabled")); }
            public Mono<Void> put(String key, ContextBuildResult context) { return Mono.empty(); }
        };
    }
}
