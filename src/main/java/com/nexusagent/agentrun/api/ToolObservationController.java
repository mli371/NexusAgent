package com.nexusagent.agentrun.api;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.observation.ToolObservationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/agent/runs/{runId}/tools")
public class ToolObservationController {
    private final ToolObservationService service;
    public ToolObservationController(ToolObservationService service) { this.service = service; }

    @GetMapping
    public Mono<ResponseEntity<JsonNode>> list(@PathVariable UUID runId, @RequestParam(defaultValue = "25") int limit,
                               @RequestParam(defaultValue = "0") int offset, @RequestHeader HttpHeaders headers) {
        return service.list(runId, AgentRunController.context(headers), limit, offset)
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body));
    }

    @GetMapping("/{observationId}")
    public Mono<ResponseEntity<JsonNode>> get(@PathVariable UUID runId, @PathVariable UUID observationId, @RequestHeader HttpHeaders headers) {
        return service.get(runId, observationId, AgentRunController.context(headers))
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body));
    }
}
