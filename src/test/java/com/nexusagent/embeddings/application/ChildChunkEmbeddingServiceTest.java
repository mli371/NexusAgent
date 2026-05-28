package com.nexusagent.embeddings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.domain.ChildChunkEmbedding;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.enterprise.ingestion.IngestionJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChildChunkEmbeddingServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.now(FIXED_CLOCK);
    private static final EmbeddingModelInfo MODEL_INFO = new EmbeddingModelInfo("local", "local-deterministic-hash-384", 384);
    private static final EmbeddingVector VECTOR = new EmbeddingVector(List.of(1.0f, 0.0f, 0.0f));

    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private EmbeddingService embeddingService;
    private ChildChunkEmbeddingRepository embeddingRepository;
    private IngestionJobService ingestionJobService;
    private AuditService auditService;
    private ChildChunkEmbeddingService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        embeddingService = mock(EmbeddingService.class);
        embeddingRepository = mock(ChildChunkEmbeddingRepository.class);
        ingestionJobService = mock(IngestionJobService.class);
        auditService = mock(AuditService.class);
        lenient().when(ingestionJobService.run(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        lenient().when(auditService.record(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        service = new ChildChunkEmbeddingService(
                documentRepository,
                chunkRepository,
                embeddingService,
                embeddingRepository,
                ingestionJobService,
                auditService,
                FIXED_CLOCK
        );

        when(embeddingService.modelInfo()).thenReturn(MODEL_INFO);
    }

    @Test
    void embedsOnlyChildChunksAndReportsCompleteStatus() {
        UUID documentId = UUID.randomUUID();
        ChildChunk firstChild = child(documentId, UUID.randomUUID(), 0, "security policy");
        ChildChunk secondChild = child(documentId, UUID.randomUUID(), 1, "access review");
        ChunkedDocument chunks = new ChunkedDocument(
                documentId,
                List.of(parent(documentId, firstChild.parentChunkId(), "Parent context should not be embedded")),
                List.of(firstChild, secondChild)
        );

        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document(documentId)));
        when(chunkRepository.findByDocumentId(documentId)).thenReturn(Mono.just(chunks));
        when(embeddingRepository.findEmbeddedChildChunkIds(documentId)).thenReturn(Flux.empty());
        when(embeddingService.embed("security policy")).thenReturn(Mono.just(VECTOR));
        when(embeddingService.embed("access review")).thenReturn(Mono.just(VECTOR));
        when(embeddingRepository.upsert(any(), eq(VECTOR), eq(MODEL_INFO), eq(FIXED_TIME)))
                .thenAnswer(invocation -> Mono.just(embeddingFor(invocation.getArgument(0))));
        when(embeddingRepository.countByDocumentId(documentId)).thenReturn(Mono.just(2L));

        StepVerifier.create(service.embedDocument(documentId))
                .assertNext(status -> {
                    assertThat(status.childChunkCount()).isEqualTo(2);
                    assertThat(status.embeddedChildChunkCount()).isEqualTo(2);
                    assertThat(status.missingChildChunkCount()).isZero();
                    assertThat(status.complete()).isTrue();
                })
                .verifyComplete();

        verify(embeddingService).embed("security policy");
        verify(embeddingService).embed("access review");
        verify(embeddingService, never()).embed("Parent context should not be embedded");
        verify(embeddingRepository).upsert(firstChild, VECTOR, MODEL_INFO, FIXED_TIME);
        verify(embeddingRepository).upsert(secondChild, VECTOR, MODEL_INFO, FIXED_TIME);
    }

    @Test
    void skipsChildChunksThatAlreadyHaveEmbeddings() {
        UUID documentId = UUID.randomUUID();
        ChildChunk firstChild = child(documentId, UUID.randomUUID(), 0, "already embedded");
        ChildChunk secondChild = child(documentId, UUID.randomUUID(), 1, "needs embedding");
        ChunkedDocument chunks = new ChunkedDocument(documentId, List.of(), List.of(firstChild, secondChild));

        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document(documentId)));
        when(chunkRepository.findByDocumentId(documentId)).thenReturn(Mono.just(chunks));
        when(embeddingRepository.findEmbeddedChildChunkIds(documentId)).thenReturn(Flux.just(firstChild.id()));
        when(embeddingService.embed("needs embedding")).thenReturn(Mono.just(VECTOR));
        when(embeddingRepository.upsert(eq(secondChild), eq(VECTOR), eq(MODEL_INFO), eq(FIXED_TIME)))
                .thenReturn(Mono.just(embeddingFor(secondChild)));
        when(embeddingRepository.countByDocumentId(documentId)).thenReturn(Mono.just(2L));

        StepVerifier.create(service.embedDocument(documentId))
                .assertNext(status -> assertThat(status.complete()).isTrue())
                .verifyComplete();

        verify(embeddingService, never()).embed("already embedded");
        verify(embeddingService).embed("needs embedding");
    }

    @Test
    void rejectsDocumentsWithoutChildChunks() {
        UUID documentId = UUID.randomUUID();
        ChunkedDocument chunks = new ChunkedDocument(documentId, List.of(), List.of());

        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document(documentId)));
        when(chunkRepository.findByDocumentId(documentId)).thenReturn(Mono.just(chunks));

        StepVerifier.create(service.embedDocument(documentId))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(error).hasMessage("Document must be chunked before embedding");
                })
                .verify();

        verify(embeddingService, never()).embed(any());
        verify(embeddingRepository, never()).upsert(any(), any(), any(), any());
    }

    private DocumentMetadata document(UUID documentId) {
        return DocumentMetadata.stored(
                documentId,
                RequestContext.DEFAULT_TENANT_ID,
                RequestContext.DEFAULT_ACTOR_ID,
                DocumentVisibility.TENANT,
                "handbook.md",
                "text/markdown",
                100,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
                "bucket",
                "documents/%s/handbook.md".formatted(documentId),
                FIXED_TIME
        );
    }

    private ParentChunk parent(UUID documentId, UUID parentId, String text) {
        return new ParentChunk(parentId, documentId, 0, text, 0, text.length(), 4, FIXED_TIME);
    }

    private ChildChunk child(UUID documentId, UUID parentId, int chunkIndex, String text) {
        return new ChildChunk(UUID.randomUUID(), documentId, parentId, chunkIndex, text, 0, text.length(), 2, FIXED_TIME);
    }

    private ChildChunkEmbedding embeddingFor(ChildChunk childChunk) {
        return new ChildChunkEmbedding(
                childChunk.id(),
                childChunk.documentId(),
                MODEL_INFO.provider(),
                MODEL_INFO.modelName(),
                MODEL_INFO.dimension(),
                FIXED_TIME,
                FIXED_TIME
        );
    }
}
