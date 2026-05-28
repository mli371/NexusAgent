package com.nexusagent.query.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class NoOpPlaceholderServicesTest {

    @Test
    void noOpRetrievalCacheDoesNotReturnCachedContext() {
        NoOpRetrievalCacheService cache = new NoOpRetrievalCacheService();
        QueryCacheKey key = new QueryCacheKey("question", List.of(), 5, 1000);

        StepVerifier.create(cache.get(key))
                .verifyComplete();
        StepVerifier.create(cache.put(key, emptyContext()))
                .verifyComplete();
        StepVerifier.create(cache.get(key))
                .verifyComplete();
    }

    @Test
    void noOpToolOutputStoreDoesNotPersistOutputs() {
        NoOpToolOutputStore store = new NoOpToolOutputStore();

        StepVerifier.create(store.save("trace", "context", "value"))
                .verifyComplete();
        StepVerifier.create(store.get("trace", "context"))
                .verifyComplete();
    }

    @Test
    void inMemorySessionStateRecordsSessionEventsLocallyOnly() {
        InMemorySessionStateService state = new InMemorySessionStateService();

        StepVerifier.create(state.recordStarted("session-1", "trace-1", "question")
                        .then(state.recordCompleted("session-1", "trace-1"))
                        .then(state.eventsForSession("session-1")))
                .assertNext(events -> {
                    assertThat(events).hasSize(2);
                    assertThat(events).extracting(InMemorySessionStateService.SessionEvent::status)
                            .containsExactly("started", "completed");
                })
                .verifyComplete();
    }

    private ContextBuildResult emptyContext() {
        return new ContextBuildResult(
                "question",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                new ContextDebugMetadata("test", 0, 0, 0, 0, 1000, 0, 0, 0)
        );
    }
}
