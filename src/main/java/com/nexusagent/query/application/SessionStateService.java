package com.nexusagent.query.application;

import reactor.core.publisher.Mono;

public interface SessionStateService {

    Mono<Void> recordStarted(String sessionId, String traceId, String question);

    Mono<Void> recordCompleted(String sessionId, String traceId);

    Mono<Void> recordFailed(String sessionId, String traceId, String errorMessage);
}
