package com.nexusagent.retrieval.api;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.retrieval.application.HybridRetrievalService;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalDebugController {

    private final HybridRetrievalService hybridRetrievalService;

    public RetrievalDebugController(HybridRetrievalService hybridRetrievalService) {
        this.hybridRetrievalService = hybridRetrievalService;
    }

    @PostMapping("/debug")
    public Mono<RetrievalDebugResponse> debug(
            @RequestBody Mono<RetrievalDebugRequest> request,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId
    ) {
        return request
                .flatMap(body -> hybridRetrievalService.retrieve(
                        body.query(),
                        body.documentIds(),
                        body.topK(),
                        RequestContext.fromHeaders(tenantId, actorId)
                ))
                .map(RetrievalDebugResponse::from);
    }
}
