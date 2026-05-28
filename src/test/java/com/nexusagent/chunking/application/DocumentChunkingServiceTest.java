package com.nexusagent.chunking.application;

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
import com.nexusagent.chunking.domain.ExtractedDocumentText;
import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentStatus;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.enterprise.ingestion.IngestionJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DocumentChunkingServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.now(FIXED_CLOCK);

    private DocumentRepository documentRepository;
    private DocumentTextExtractionService extractionService;
    private ChunkRepository chunkRepository;
    private IngestionJobService ingestionJobService;
    private AuditService auditService;
    private DocumentChunkingService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        extractionService = mock(DocumentTextExtractionService.class);
        chunkRepository = mock(ChunkRepository.class);
        ingestionJobService = mock(IngestionJobService.class);
        auditService = mock(AuditService.class);
        lenient().when(ingestionJobService.run(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        lenient().when(auditService.record(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        ChunkingProperties properties = new ChunkingProperties();
        properties.setParentMaxChars(120);
        properties.setChildMaxChars(50);
        properties.setChildOverlapChars(10);
        service = new DocumentChunkingService(
                documentRepository,
                extractionService,
                new ParentChildChunker(properties),
                chunkRepository,
                ingestionJobService,
                auditService,
                FIXED_CLOCK
        );
    }

    @Test
    void firstChunkingCreatesChunksAndMarksDocumentChunked() {
        UUID documentId = UUID.randomUUID();
        DocumentMetadata document = document(documentId);
        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document));
        when(chunkRepository.findByDocumentId(documentId)).thenReturn(Mono.just(emptyChunks(documentId)));
        when(extractionService.extract(document)).thenReturn(Mono.just(new ExtractedDocumentText(
                documentId,
                "Alpha beta gamma delta epsilon.\n\nSecond paragraph has more text for child chunks.",
                "text/plain"
        )));
        when(chunkRepository.replaceChunks(eq(documentId), any(ParentChildChunkPlan.class), eq(FIXED_TIME)))
                .thenAnswer(invocation -> Mono.just(chunkedFromPlan(documentId, invocation.getArgument(1), FIXED_TIME)));
        when(documentRepository.updateStatus(documentId, DocumentStatus.CHUNKED, FIXED_TIME)).thenReturn(Mono.empty());

        StepVerifier.create(service.extractAndChunk(documentId))
                .assertNext(chunked -> {
                    assertThat(chunked.parentChunks()).isNotEmpty();
                    assertThat(chunked.childChunks()).isNotEmpty();
                })
                .verifyComplete();

        verify(chunkRepository).replaceChunks(eq(documentId), any(ParentChildChunkPlan.class), eq(FIXED_TIME));
        verify(documentRepository).updateStatus(documentId, DocumentStatus.CHUNKED, FIXED_TIME);
    }

    @Test
    void secondChunkingWithoutForceReturnsExistingChunksWithSameIdsAndCreatedAt() {
        UUID documentId = UUID.randomUUID();
        DocumentMetadata document = document(documentId, DocumentStatus.CHUNKED);
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        OffsetDateTime originalCreatedAt = OffsetDateTime.parse("2026-05-10T10:15:30Z");
        ChunkedDocument existing = chunked(documentId, parentId, childId, originalCreatedAt);

        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document));
        when(chunkRepository.findByDocumentId(documentId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.extractAndChunk(documentId))
                .assertNext(chunked -> {
                    assertThat(chunked.parentChunks()).hasSize(1);
                    assertThat(chunked.childChunks()).hasSize(1);
                    assertThat(chunked.parentChunks().get(0).id()).isEqualTo(parentId);
                    assertThat(chunked.childChunks().get(0).id()).isEqualTo(childId);
                    assertThat(chunked.parentChunks().get(0).createdAt()).isEqualTo(originalCreatedAt);
                    assertThat(chunked.childChunks().get(0).createdAt()).isEqualTo(originalCreatedAt);
                })
                .verifyComplete();

        verify(extractionService, never()).extract(any());
        verify(chunkRepository, never()).replaceChunks(any(), any(), any());
        verify(documentRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void existingChunksWithStoredStatusMarksDocumentChunkedWithoutRegenerating() {
        UUID documentId = UUID.randomUUID();
        DocumentMetadata document = document(documentId);
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        ChunkedDocument existing = chunked(documentId, parentId, childId, FIXED_TIME);

        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document));
        when(chunkRepository.findByDocumentId(documentId)).thenReturn(Mono.just(existing));
        when(documentRepository.updateStatus(documentId, DocumentStatus.CHUNKED, FIXED_TIME)).thenReturn(Mono.empty());

        StepVerifier.create(service.extractAndChunk(documentId))
                .assertNext(chunked -> {
                    assertThat(chunked.parentChunks().get(0).id()).isEqualTo(parentId);
                    assertThat(chunked.childChunks().get(0).id()).isEqualTo(childId);
                })
                .verifyComplete();

        verify(extractionService, never()).extract(any());
        verify(chunkRepository, never()).replaceChunks(any(), any(), any());
        verify(documentRepository).updateStatus(documentId, DocumentStatus.CHUNKED, FIXED_TIME);
    }

    @Test
    void forceChunkingRegeneratesChunksAndKeepsCountsStable() {
        UUID documentId = UUID.randomUUID();
        DocumentMetadata document = document(documentId);
        UUID originalParentId = UUID.randomUUID();
        UUID originalChildId = UUID.randomUUID();
        UUID regeneratedParentId = UUID.randomUUID();
        UUID regeneratedChildId = UUID.randomUUID();
        ChunkedDocument regenerated = chunked(documentId, regeneratedParentId, regeneratedChildId, FIXED_TIME);

        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document));
        when(extractionService.extract(document)).thenReturn(Mono.just(new ExtractedDocumentText(
                documentId,
                "Alpha beta gamma delta epsilon.",
                "text/plain"
        )));
        when(chunkRepository.replaceChunks(eq(documentId), any(ParentChildChunkPlan.class), eq(FIXED_TIME)))
                .thenReturn(Mono.just(regenerated));
        when(documentRepository.updateStatus(documentId, DocumentStatus.CHUNKED, FIXED_TIME)).thenReturn(Mono.empty());

        StepVerifier.create(service.extractAndChunk(documentId, true))
                .assertNext(chunked -> {
                    assertThat(chunked.parentChunks()).hasSize(1);
                    assertThat(chunked.childChunks()).hasSize(1);
                    assertThat(chunked.parentChunks().get(0).id()).isEqualTo(regeneratedParentId);
                    assertThat(chunked.childChunks().get(0).id()).isEqualTo(regeneratedChildId);
                    assertThat(chunked.parentChunks().get(0).id()).isNotEqualTo(originalParentId);
                    assertThat(chunked.childChunks().get(0).id()).isNotEqualTo(originalChildId);
                })
                .verifyComplete();

        verify(chunkRepository).replaceChunks(eq(documentId), any(ParentChildChunkPlan.class), eq(FIXED_TIME));
        verify(documentRepository).updateStatus(documentId, DocumentStatus.CHUNKED, FIXED_TIME);
    }

    @Test
    void rejectsEmptyExtractedTextBeforePersistingChunksAndMarksChunkingFailed() {
        UUID documentId = UUID.randomUUID();
        DocumentMetadata document = document(documentId);

        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document));
        when(chunkRepository.findByDocumentId(documentId)).thenReturn(Mono.just(emptyChunks(documentId)));
        when(extractionService.extract(document)).thenReturn(Mono.just(new ExtractedDocumentText(documentId, " \n ", "text/plain")));
        when(documentRepository.updateStatus(documentId, DocumentStatus.CHUNKING_FAILED, FIXED_TIME)).thenReturn(Mono.empty());

        StepVerifier.create(service.extractAndChunk(documentId))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(error).hasMessage("Extracted document text is empty");
                })
                .verify();

        verify(chunkRepository, never()).replaceChunks(any(), any(), any());
        verify(documentRepository).updateStatus(documentId, DocumentStatus.CHUNKING_FAILED, FIXED_TIME);
    }

    private DocumentMetadata document(UUID id) {
        return document(id, DocumentStatus.STORED);
    }

    private DocumentMetadata document(UUID id, DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new DocumentMetadata(
                id,
                "default",
                "anonymous",
                DocumentVisibility.TENANT,
                "notes.txt",
                "text/plain",
                100,
                "sha",
                "bucket",
                "documents/%s/notes.txt".formatted(id),
                status,
                now,
                now
        );
    }

    private ChunkedDocument emptyChunks(UUID documentId) {
        return new ChunkedDocument(documentId, List.of(), List.of());
    }

    private ChunkedDocument chunked(UUID documentId, UUID parentId, UUID childId, OffsetDateTime createdAt) {
        ParentChunk parent = new ParentChunk(parentId, documentId, 0, "Parent text", 0, 11, 2, createdAt);
        ChildChunk child = new ChildChunk(childId, documentId, parentId, 0, "Child text", 0, 10, 2, createdAt);
        return new ChunkedDocument(documentId, List.of(parent), List.of(child));
    }

    private ChunkedDocument chunkedFromPlan(UUID documentId, ParentChildChunkPlan plan, OffsetDateTime createdAt) {
        List<ParentChunk> parents = plan.parentChunks().stream()
                .map(parent -> new ParentChunk(
                        parent.id(),
                        documentId,
                        parent.chunkIndex(),
                        parent.text(),
                        parent.charStart(),
                        parent.charEnd(),
                        parent.tokenCount(),
                        createdAt
                ))
                .toList();
        List<ChildChunk> children = plan.childChunks().stream()
                .map(child -> new ChildChunk(
                        child.id(),
                        documentId,
                        child.parentChunkId(),
                        child.chunkIndex(),
                        child.text(),
                        child.charStart(),
                        child.charEnd(),
                        child.tokenCount(),
                        createdAt
                ))
                .toList();
        return new ChunkedDocument(documentId, parents, children);
    }
}
