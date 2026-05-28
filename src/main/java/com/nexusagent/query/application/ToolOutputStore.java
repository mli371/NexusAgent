package com.nexusagent.query.application;

import reactor.core.publisher.Mono;

public interface ToolOutputStore {

    Mono<Void> save(String traceId, String key, Object value);

    Mono<Object> get(String traceId, String key);
}
