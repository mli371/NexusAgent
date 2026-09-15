package com.nexusagent.agentrun.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import com.nexusagent.common.context.RequestContext;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class RunEventStreamService {
    private final AgentRunService runs;
    private final AgentRunRepository repository;
    private final AgentRunProperties properties;
    private final RunJson json;

    public RunEventStreamService(AgentRunService runs, AgentRunRepository repository, AgentRunProperties properties, RunJson json) {
        this.runs = runs; this.repository = repository; this.properties = properties; this.json = json;
    }

    public static long cursor(String lastEventId, long after) {
        if (lastEventId == null) {
            if (after < 0) { throw RunException.invalid("afterSequence must not be negative"); }
            return after;
        }
        if (!lastEventId.matches("[0-9]{1,19}")) { throw RunException.invalid("Last-Event-ID must be a non-negative sequence"); }
        try { return Long.parseLong(lastEventId); }
        catch (NumberFormatException error) { throw RunException.invalid("Last-Event-ID is too large"); }
    }

    // The Mono resolves before response headers, so initial access/cursor errors remain HTTP 4xx/5xx.
    public Mono<Flux<ServerSentEvent<JsonNode>>> open(UUID id, long after, RequestContext context) {
        return runs.owned(id, context).map(initial -> {
            if (after < 0 || after > initial.eventSequence()) { throw RunException.invalid("Event cursor exceeds the run history"); }
            return Flux.defer(() -> page(id, after, context)
                    .expand(page -> page.done() ? Mono.empty()
                            : Mono.delay(page.more() ? Duration.ZERO : properties.getEventPollInterval())
                                    .then(page(id, page.cursor(), context)), 1)
                    .concatMap(page -> {
                        if (page.events().isEmpty()) {
                            return page.done() ? Flux.empty()
                                    : Flux.just(ServerSentEvent.<JsonNode>builder().comment("keep-alive").build());
                        }
                        return Flux.fromIterable(page.events()).map(event -> {
                            ObjectNode data = event.deepCopy();
                            data.put("runId", id.toString()).put("traceId", initial.traceId()).put("executionMode", initial.executionMode());
                            return ServerSentEvent.<JsonNode>builder(data).id(event.path("sequence").asText())
                                    .event(event.path("eventType").asText()).build();
                        });
                    }, 1))
                    .onErrorResume(error -> Flux.just(ServerSentEvent.<JsonNode>builder(json.object()
                            .put("runId", id.toString()).put("traceId", initial.traceId())
                            .put("code", error instanceof RunException re && re.status() == 404 ? "ACCESS_REVOKED" : "STREAM_UNAVAILABLE")
                            .put("message", "Event stream closed; verify access and reconnect with the last received event ID"))
                            .event("error").build()));
        });
    }

    private Mono<Page> page(UUID id, long after, RequestContext context) {
        // Recheck ownership and document visibility on every page, including reconnects.
        return runs.owned(id, context).flatMap(run -> repository.events(id, after).take(100).collectList().map(events -> {
            long next = events.isEmpty() ? after : events.get(events.size() - 1).path("sequence").asLong();
            return new Page(events, next, next < run.eventSequence(), run.terminal() && next >= run.eventSequence());
        }));
    }

    private record Page(List<JsonNode> events, long cursor, boolean more, boolean done) { }
}
