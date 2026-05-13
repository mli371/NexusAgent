package com.nexusagent.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.embeddings.domain.VectorSearchResult;
import com.nexusagent.embeddings.repository.VectorSearchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SemanticRetrievalServiceTest {

    @Mock
    EmbeddingService embeddingService;

    @Mock
    VectorSearchRepository vectorSearchRepository;

    @Test
    void embedsQueryAndRanksVectorSearchResults() {
        SemanticRetrievalService service = new SemanticRetrievalService(embeddingService, vectorSearchRepository);
        EmbeddingVector queryEmbedding = new EmbeddingVector(List.of(1.0f, 0.0f, 0.0f));
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        List<UUID> documentIds = List.of(documentId);

        when(embeddingService.embed("security policy")).thenReturn(Mono.just(queryEmbedding));
        when(vectorSearchRepository.search(queryEmbedding, documentIds, 3)).thenReturn(Flux.just(
                new VectorSearchResult(
                        childId,
                        documentId,
                        parentId,
                        7,
                        "security policy preview",
                        0.14d
                )
        ));

        StepVerifier.create(service.retrieve("security policy", documentIds, 3).collectList())
                .assertNext(candidates -> {
                    assertThat(candidates).hasSize(1);
                    assertThat(candidates.get(0).childChunkId()).isEqualTo(childId);
                    assertThat(candidates.get(0).vectorRank()).isEqualTo(1);
                    assertThat(candidates.get(0).vectorDistance()).isEqualTo(0.14d);
                    assertThat(candidates.get(0).previewText()).contains("security policy");
                })
                .verifyComplete();

        verify(embeddingService).embed("security policy");
        verify(vectorSearchRepository).search(queryEmbedding, documentIds, 3);
    }

    @Test
    void preservesRepositoryOrderingAndAssignsOneBasedVectorRanks() {
        SemanticRetrievalService service = new SemanticRetrievalService(embeddingService, vectorSearchRepository);
        EmbeddingVector queryEmbedding = new EmbeddingVector(List.of(1.0f, 0.0f, 0.0f));
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID firstChildId = UUID.randomUUID();
        UUID secondChildId = UUID.randomUUID();

        when(embeddingService.embed("security policy")).thenReturn(Mono.just(queryEmbedding));
        when(vectorSearchRepository.search(queryEmbedding, List.of(), 2)).thenReturn(Flux.just(
                new VectorSearchResult(firstChildId, documentId, parentId, 0, "best vector hit", 0.10d),
                new VectorSearchResult(secondChildId, documentId, parentId, 1, "second vector hit", 0.20d)
        ));

        StepVerifier.create(service.retrieve("security policy", List.of(), 2).collectList())
                .assertNext(candidates -> {
                    assertThat(candidates).extracting(candidate -> candidate.childChunkId())
                            .containsExactly(firstChildId, secondChildId);
                    assertThat(candidates).extracting(candidate -> candidate.vectorRank())
                            .containsExactly(1, 2);
                    assertThat(candidates).extracting(candidate -> candidate.vectorDistance())
                            .containsExactly(0.10d, 0.20d);
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyWhenNoEmbeddedChildChunksMatch() {
        SemanticRetrievalService service = new SemanticRetrievalService(embeddingService, vectorSearchRepository);
        EmbeddingVector queryEmbedding = new EmbeddingVector(List.of(1.0f, 0.0f, 0.0f));

        when(embeddingService.embed("security policy")).thenReturn(Mono.just(queryEmbedding));
        when(vectorSearchRepository.search(queryEmbedding, List.of(), 5)).thenReturn(Flux.empty());

        StepVerifier.create(service.retrieve("security policy", List.of(), 5).collectList())
                .assertNext(candidates -> assertThat(candidates).isEmpty())
                .verifyComplete();
    }
}
