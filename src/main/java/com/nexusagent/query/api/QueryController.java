package com.nexusagent.query.api;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.query.application.QueryOrchestrationService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

    private final QueryOrchestrationService queryOrchestrationService;

    public QueryController(QueryOrchestrationService queryOrchestrationService) {
        this.queryOrchestrationService = queryOrchestrationService;
    }

    @PostMapping
    public Mono<QueryResponse> query(
            @RequestBody Mono<QueryRequest> request,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId,
            @RequestHeader(name = RequestContext.TRACE_HEADER, required = false) String traceId
    ) {
        return request.flatMap(body -> queryOrchestrationService.execute(
                body,
                traceId,
                RequestContext.fromHeaders(tenantId, actorId)
        ));
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<QueryStreamEvent>> stream(
            @RequestBody Mono<QueryRequest> request,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId,
            @RequestHeader(name = RequestContext.TRACE_HEADER, required = false) String traceId
    ) {
        return request.flatMapMany(body -> queryOrchestrationService.stream(
                body,
                traceId,
                RequestContext.fromHeaders(tenantId, actorId)
        ));
    }
}
