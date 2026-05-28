package com.nexusagent.context.api;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.context.application.ContextBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/context")
public class ContextDebugController {

    private final ContextBuilder contextBuilder;

    public ContextDebugController(ContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @PostMapping("/debug")
    public Mono<ContextDebugResponse> debug(
            @RequestBody Mono<ContextDebugRequest> request,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId
    ) {
        return request
                .flatMap(body -> contextBuilder.build(
                        body.query(),
                        body.documentIds(),
                        body.topK(),
                        body.contextBudgetChars(),
                        RequestContext.fromHeaders(tenantId, actorId)
                ))
                .map(ContextDebugResponse::from);
    }
}
