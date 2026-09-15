package com.nexusagent.agentrun.api;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.application.RunEventStreamService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class RunEventStreamController {
    private final RunEventStreamService streams;

    public RunEventStreamController(RunEventStreamService streams) { this.streams = streams; }

    @GetMapping(value = "/api/v1/agent/runs/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<JsonNode>>>> stream(@PathVariable UUID runId,
            @RequestHeader HttpHeaders headers, @RequestParam(defaultValue = "0") long afterSequence) {
        long cursor = RunEventStreamService.cursor(headers.getFirst("Last-Event-ID"), afterSequence);
        return streams.open(runId, cursor, AgentRunController.context(headers)).map(events -> ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no").body(events));
    }
}
