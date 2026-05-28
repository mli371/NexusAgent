package com.nexusagent.enterprise.ingestion;

import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/ingestion-jobs")
public class IngestionJobController {

    private final IngestionJobService ingestionJobService;

    public IngestionJobController(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @GetMapping
    public Flux<IngestionJobResponse> list(
            @PathVariable UUID documentId,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId
    ) {
        return ingestionJobService.findForDocument(documentId, RequestContext.fromHeaders(tenantId, actorId))
                .map(IngestionJobResponse::from);
    }
}
