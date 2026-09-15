package com.nexusagent.common.observation;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import reactor.core.publisher.Mono;

/** Optional request-local observation; existing retrieval callers need no observer. */
public interface StageObservation {
    Object KEY = StageObservation.class;

    void started(String stage);
    void finished(String stage, String status, Map<String, Object> summary);

    static <T> Mono<T> observe(String stage, Supplier<Mono<T>> work,
                              Function<T, Map<String, Object>> summary) {
        return Mono.deferContextual(context -> {
            if (!context.hasKey(KEY)) {
                return Mono.defer(work);
            }
            StageObservation observer = context.get(KEY);
            observer.started(stage);
            return Mono.defer(work)
                    .doOnSuccess(value -> observer.finished(stage, "succeeded",
                            value == null ? Map.of() : summary.apply(value)))
                    .doOnError(error -> observer.finished(stage, "failed", Map.of()))
                    .doOnCancel(() -> observer.finished(stage, "cancelled", Map.of()));
        });
    }
}
