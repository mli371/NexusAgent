package com.nexusagent.query.live;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.observation.StageObservation;
import com.nexusagent.query.api.QueryResponse;
import com.nexusagent.query.api.QueryStageEvent;
import com.nexusagent.query.api.QueryStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Bounded, per-request trace. No prompts, context text, vectors or model reasoning are recorded. */
final class QueryTrace implements StageObservation {
    private final String traceId;
    private final boolean debug;
    private final ObjectMapper mapper;
    private final Map<String, Long> starts = new HashMap<>();
    private final List<QueryStageEvent> history = new ArrayList<>();
    private final Sinks.Many<ServerSentEvent<QueryStreamEvent>> events = Sinks.many().replay().limit(64);
    private long sequence;
    private boolean closed;

    QueryTrace(String traceId, boolean debug, ObjectMapper mapper) {
        this.traceId = traceId;
        this.debug = debug;
        this.mapper = mapper;
        emit(new QueryStreamEvent("received", traceId, "query received", null));
    }

    @Override
    public synchronized void started(String stage) {
        starts.put(stage, System.nanoTime());
        record(stage, "running", 0, Map.of());
    }

    @Override
    public synchronized void finished(String stage, String status, Map<String, Object> summary) {
        Long start = starts.remove(stage);
        record(stage, status, start == null ? 0 : (System.nanoTime() - start) / 1_000_000, summary);
    }

    synchronized void skipped(String stage, String reason) {
        record(stage, "skipped", 0, Map.of("reason", reason));
    }

    private void record(String stage, String status, long elapsed, Map<String, Object> summary) {
        if (closed) { return; }
        if (history.size() >= 48) { throw new IllegalStateException("Query stage limit exceeded"); }
        Map<String, Object> safeSummary = null;
        if (debug) {
            try {
                safeSummary = mapper.writeValueAsBytes(summary).length <= 16 * 1024
                        ? Map.copyOf(summary) : Map.of("omitted", "summary_size_limit");
            } catch (Exception ignored) {
                safeSummary = Map.of("omitted", "unserializable_summary");
            }
        }
        QueryStageEvent event = new QueryStageEvent(++sequence, stage, 1, status, Instant.now(), elapsed, safeSummary);
        history.add(event);
        emit(new QueryStreamEvent("stage", traceId, null, null, event, null));
    }

    synchronized List<QueryStageEvent> history() { return List.copyOf(history); }
    Flux<ServerSentEvent<QueryStreamEvent>> events() { return events.asFlux(); }

    synchronized void complete(QueryResponse response) {
        if (closed) { return; }
        emit(new QueryStreamEvent("message", traceId, response.answer(), null));
        emit(new QueryStreamEvent("completed", traceId, "query completed", response));
        close();
    }

    synchronized void fail(Throwable error) {
        if (closed) { return; }
        QueryFailure failure = QueryFailure.safe(traceId, error);
        emit(new QueryStreamEvent("error", traceId, failure.getMessage(), null, null, failure.code()));
        close();
    }

    synchronized void close() {
        closed = true;
        events.tryEmitComplete();
    }

    private void emit(QueryStreamEvent value) {
        events.tryEmitNext(ServerSentEvent.builder(value).event(value.type()).build()).orThrow();
    }
}
