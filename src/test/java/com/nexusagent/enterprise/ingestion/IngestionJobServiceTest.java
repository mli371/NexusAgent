package com.nexusagent.enterprise.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.nexusagent.documents.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IngestionJobServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-27T12:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    @Test
    void marksJobSucceededWhenWorkCompletes() {
        IngestionJobRepository repository = org.mockito.Mockito.mock(IngestionJobRepository.class);
        DocumentRepository documentRepository = org.mockito.Mockito.mock(DocumentRepository.class);
        IngestionJobService service = new IngestionJobService(repository, documentRepository, CLOCK);
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestionJob running = job(jobId, documentId, IngestionJobStatus.RUNNING, null);
        IngestionJob succeeded = job(jobId, documentId, IngestionJobStatus.SUCCEEDED, null);

        when(repository.createRunning(documentId, "tenant-a", IngestionJobType.CHUNK, NOW))
                .thenReturn(Mono.just(running));
        when(repository.markSucceeded(jobId, NOW)).thenReturn(Mono.just(succeeded));

        StepVerifier.create(service.run(documentId, "tenant-a", IngestionJobType.CHUNK, Mono.just("done")))
                .expectNext("done")
                .verifyComplete();

        verify(repository).markSucceeded(jobId, NOW);
    }

    @Test
    void marksJobFailedWhenWorkFails() {
        IngestionJobRepository repository = org.mockito.Mockito.mock(IngestionJobRepository.class);
        DocumentRepository documentRepository = org.mockito.Mockito.mock(DocumentRepository.class);
        IngestionJobService service = new IngestionJobService(repository, documentRepository, CLOCK);
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        RuntimeException failure = new RuntimeException("chunk failed");
        IngestionJob running = job(jobId, documentId, IngestionJobStatus.RUNNING, null);
        IngestionJob failed = job(jobId, documentId, IngestionJobStatus.FAILED, "chunk failed");

        when(repository.createRunning(documentId, "tenant-a", IngestionJobType.CHUNK, NOW))
                .thenReturn(Mono.just(running));
        when(repository.markFailed(eq(jobId), eq("chunk failed"), eq("UNKNOWN"), any())).thenReturn(Mono.just(failed));

        StepVerifier.create(service.run(documentId, "tenant-a", IngestionJobType.CHUNK, Mono.error(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();

        verify(repository).markFailed(eq(jobId), eq("chunk failed"), eq("UNKNOWN"), any());
        assertThat(failed.status()).isEqualTo(IngestionJobStatus.FAILED);
    }

    @Test
    void preservesOriginalFailureIfSavingFailedStatusAlsoFails() {
        IngestionJobRepository repository = org.mockito.Mockito.mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(repository, org.mockito.Mockito.mock(DocumentRepository.class), CLOCK);
        UUID id = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        var original = new IngestionFailure("EMPTY_TEXT", "Extracted document text is empty");
        when(repository.createRunning(document, "tenant-a", IngestionJobType.CHUNK, NOW))
                .thenReturn(Mono.just(job(id, document, IngestionJobStatus.RUNNING, null)));
        when(repository.markFailed(eq(id), any(), eq("EMPTY_TEXT"), any()))
                .thenReturn(Mono.error(new RuntimeException("status storage unavailable")));
        StepVerifier.create(service.run(document, "tenant-a", IngestionJobType.CHUNK, Mono.error(original)))
                .expectErrorMatches(error -> error == original).verify();
    }

    @Test
    void typedClassificationNeverGuessesFromErrorMessageText() {
        assertThat(IngestionFailure.code(new RuntimeException("timeout TRANSIENT_DEPENDENCY"))).isEqualTo("UNKNOWN");
        assertThat(IngestionFailure.code(new RuntimeException(new java.util.concurrent.TimeoutException())))
                .isEqualTo("TRANSIENT_DEPENDENCY");
        assertThat(IngestionFailure.code(new IngestionFailure("UNSUPPORTED_TYPE", "unsupported"))).isEqualTo("UNSUPPORTED_TYPE");
    }

    private IngestionJob job(
            UUID jobId,
            UUID documentId,
            IngestionJobStatus status,
            String errorMessage
    ) {
        return new IngestionJob(
                jobId,
                documentId,
                "tenant-a",
                IngestionJobType.CHUNK,
                status,
                errorMessage,
                NOW,
                status == IngestionJobStatus.RUNNING ? null : NOW,
                NOW,
                NOW
        );
    }
}
