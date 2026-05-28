package com.nexusagent.enterprise.ingestion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.documents.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class IngestionJobService {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobService.class);

    private final IngestionJobRepository ingestionJobRepository;
    private final DocumentRepository documentRepository;
    private final Clock clock;

    public IngestionJobService(
            IngestionJobRepository ingestionJobRepository,
            DocumentRepository documentRepository,
            Clock clock
    ) {
        this.ingestionJobRepository = ingestionJobRepository;
        this.documentRepository = documentRepository;
        this.clock = clock;
    }

    public <T> Mono<T> run(UUID documentId, String tenantId, IngestionJobType jobType, Mono<T> work) {
        return ingestionJobRepository.createRunning(documentId, tenantId, jobType, OffsetDateTime.now(clock))
                .flatMap(job -> work
                        .flatMap(result -> ingestionJobRepository.markSucceeded(job.id(), OffsetDateTime.now(clock))
                                .doOnSuccess(updated -> log.info(
                                        "ingestion_job_succeeded jobId={} documentId={} tenantId={} jobType={}",
                                        updated.id(),
                                        updated.documentId(),
                                        updated.tenantId(),
                                        updated.jobType()
                                ))
                                .thenReturn(result))
                        .onErrorResume(error -> ingestionJobRepository.markFailed(
                                        job.id(),
                                        error.getMessage(),
                                        OffsetDateTime.now(clock)
                                )
                                .doOnSuccess(updated -> log.warn(
                                        "ingestion_job_failed jobId={} documentId={} tenantId={} jobType={} reason={}",
                                        updated.id(),
                                        updated.documentId(),
                                        updated.tenantId(),
                                        updated.jobType(),
                                        updated.errorMessage()
                                ))
                                .then(Mono.error(error))));
    }

    public Flux<IngestionJob> findForDocument(UUID documentId, RequestContext context) {
        RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
        return documentRepository.findById(documentId, effectiveContext)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + documentId)))
                .thenMany(ingestionJobRepository.findByDocumentIdAndTenant(documentId, effectiveContext.tenantId()));
    }
}
