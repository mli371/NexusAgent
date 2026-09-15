package com.nexusagent.query.api;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.query.application.QueryOrchestrationService;
import com.nexusagent.query.live.LiveQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final LiveQueryService liveQueries;

    public QueryController(QueryOrchestrationService queryOrchestrationService) {
        this.queryOrchestrationService = queryOrchestrationService;
        this.liveQueries = null;
    }

    @Autowired
    public QueryController(QueryOrchestrationService queryOrchestrationService, ObjectProvider<LiveQueryService> liveQueries) {
        this.queryOrchestrationService = queryOrchestrationService;
        this.liveQueries = liveQueries.getIfAvailable();
    }

    @PostMapping
    public Mono<QueryResponse> query(
            @RequestBody Mono<QueryRequest> request,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId,
            @RequestHeader(name = RequestContext.TRACE_HEADER, required = false) String traceId
    ) {
        return request.flatMap(body -> liveQueries != null ? liveQueries.execute(body, traceId, RequestContext.fromHeaders(tenantId, actorId))
                : queryOrchestrationService.execute(
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
        return request.flatMapMany(body -> liveQueries != null ? liveQueries.stream(body, traceId, RequestContext.fromHeaders(tenantId, actorId))
                : queryOrchestrationService.stream(
                body,
                traceId,
                RequestContext.fromHeaders(tenantId, actorId)
        ));
    }
}
