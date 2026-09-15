package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.observation.StageObservation;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class QueryTraceTest {
    @Test
    void actualParallelCompletionOrderIsNotPreannounced() {
        QueryTrace trace = new QueryTrace("trace", true, new ObjectMapper().findAndRegisterModules());
        StepVerifier.withVirtualTime(() -> Mono.zip(
                StageObservation.observe("slow", () -> Mono.delay(Duration.ofSeconds(2)), ignored -> Map.of()),
                StageObservation.observe("fast", () -> Mono.delay(Duration.ofSeconds(1)), ignored -> Map.of()))
                .contextWrite(context -> context.put(StageObservation.KEY, trace)))
                .thenAwait(Duration.ofSeconds(1))
                .then(() -> assertThat(trace.history()).extracting(event -> event.stage() + ":" + event.status())
                        .containsExactly("slow:running", "fast:running", "fast:succeeded"))
                .thenAwait(Duration.ofSeconds(1)).expectNextCount(1).verifyComplete();
        assertThat(trace.history().get(3).stage()).isEqualTo("slow");
        trace.close();
    }

    @Test
    void oversizeStageSummaryIsOmittedAndHistoryRetainsMonotonicSequence() {
        QueryTrace trace = new QueryTrace("trace", true, new ObjectMapper().findAndRegisterModules());
        trace.started("test");
        trace.finished("test", "succeeded", Map.of("value", "x".repeat(17000)));
        assertThat(trace.history().get(1).summary()).isEqualTo(Map.of("omitted", "summary_size_limit"));
        assertThat(trace.history().get(0).attempt()).isEqualTo(1);
        assertThat(trace.history().get(1).sequence()).isEqualTo(2);
        trace.close();
    }
}
