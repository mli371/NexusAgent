package com.nexusagent.agent.api;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.agent.application.AgentOrchestrator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentQueryController {

    private final AgentOrchestrator agentOrchestrator;

    public AgentQueryController(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    @PostMapping("/query")
    public Mono<AgentQueryResponse> query(
            @RequestBody Mono<AgentQueryRequest> request,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId,
            @RequestHeader(name = RequestContext.TRACE_HEADER, required = false) String traceId
    ) {
        return request.flatMap(body -> agentOrchestrator.query(
                body,
                RequestContext.fromHeaders(tenantId, actorId),
                traceId
        ));
    }
}
