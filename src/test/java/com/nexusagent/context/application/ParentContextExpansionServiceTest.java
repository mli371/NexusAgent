package com.nexusagent.context.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.context.domain.RerankScoreBreakdown;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ParentContextExpansionServiceTest {

    @Mock
    DocumentRepository documentRepository;

    @Mock
    ChunkRepository chunkRepository;

    @Test
    void expandsRerankedChildCandidateToParentContext() {
        ParentContextExpansionService service = new ParentContextExpansionService(documentRepository, chunkRepository);
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        DocumentMetadata document = document(documentId, now);
        ParentChunk parent = new ParentChunk(parentId, documentId, 0, "Parent context text", 0, 19, 3, now);
        ChildChunk child = new ChildChunk(childId, documentId, parentId, 0, "Parent context", 0, 14, 2, now);

        when(documentRepository.findById(documentId, RequestContext.defaults())).thenReturn(Mono.just(document));
        when(chunkRepository.findByDocumentId(documentId)).thenReturn(Mono.just(
                new ChunkedDocument(documentId, List.of(parent), List.of(child))
        ));

        StepVerifier.create(service.expand(List.of(reranked(documentId, parentId, childId))))
                .assertNext(expanded -> {
                    assertThat(expanded).hasSize(1);
                    assertThat(expanded.get(0).document().originalFilename()).isEqualTo("notes.txt");
                    assertThat(expanded.get(0).parentChunk().id()).isEqualTo(parentId);
                    assertThat(expanded.get(0).childChunk().id()).isEqualTo(childId);
                })
                .verifyComplete();
    }

    private RerankedCandidate reranked(UUID documentId, UUID parentId, UUID childId) {
        return new RerankedCandidate(
                new FusedRetrievalCandidate(
                        childId,
                        documentId,
                        parentId,
                        0,
                        "Parent context",
                        RetrievalSource.BOTH,
                        1,
                        0.1d,
                        1,
                        0.9d,
                        0.03d
                ),
                1,
                1,
                3.0d,
                new RerankScoreBreakdown(3.0d, 0.0d, 0.2d, 0.0d),
                "test"
        );
    }

    private DocumentMetadata document(UUID documentId, OffsetDateTime timestamp) {
        return DocumentMetadata.stored(
                documentId,
                "notes.txt",
                "text/plain",
                100,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
                "bucket",
                "documents/%s/notes.txt".formatted(documentId),
                timestamp
        );
    }
}
