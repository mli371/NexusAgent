package com.nexusagent.agentrun.api;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.approval.ApprovalService;
import com.nexusagent.common.context.RequestContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/agent/runs")
public class AgentRunController {
    private final AgentRunService service;
    private final ApprovalService approvals;

    public AgentRunController(AgentRunService service, ApprovalService approvals) { this.service = service; this.approvals = approvals; }

    @PostMapping("/{runId}/approvals/{approvalId}")
    public Mono<JsonNode> decide(@PathVariable UUID runId, @PathVariable UUID approvalId,
                                 @RequestBody JsonNode body, @RequestHeader HttpHeaders headers) {
        RunJson.fields(body, "decision");
        return approvals.decide(runId, approvalId, RunJson.text(body, "decision", 20), context(headers));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<JsonNode> create(@RequestBody JsonNode body, @RequestHeader HttpHeaders headers) {
        return service.create(body, context(headers), headers.getFirst("Idempotency-Key"), headers.getFirst("X-Trace-Id"));
    }

    @GetMapping("/{runId}")
    public Mono<ResponseEntity<JsonNode>> get(@PathVariable UUID runId, @RequestHeader HttpHeaders headers) {
        return service.get(runId, context(headers)).map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body));
    }

    @PostMapping("/{runId}/cancel")
    public Mono<JsonNode> cancel(@PathVariable UUID runId, @RequestHeader HttpHeaders headers) {
        return service.cancel(runId, context(headers));
    }

    @GetMapping("/{runId}/events")
    public Mono<JsonNode> events(@PathVariable UUID runId, @RequestParam(defaultValue = "0") long afterSequence,
                                  @RequestHeader HttpHeaders headers) {
        return service.events(runId, afterSequence, context(headers));
    }

    static RequestContext context(HttpHeaders headers) {
        String tenant = headers.getFirst(RequestContext.TENANT_HEADER);
        String actor = headers.getFirst(RequestContext.ACTOR_HEADER);
        if (tenant == null || tenant.isBlank() || actor == null || actor.isBlank()) {
            throw RunException.invalid("X-Tenant-Id and X-Actor-Id are required for agent runs");
        }
        return RequestContext.fromHeaders(tenant, actor);
    }
}
