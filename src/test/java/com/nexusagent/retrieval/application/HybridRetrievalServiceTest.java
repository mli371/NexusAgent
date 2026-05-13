package com.nexusagent.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.retrieval.domain.FullTextRetrievalCandidate;
import com.nexusagent.retrieval.domain.RetrievalSource;
import com.nexusagent.retrieval.domain.SemanticRetrievalCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class HybridRetrievalServiceTest {

    @Mock
    SemanticRetrievalService semanticRetrievalService;

    @Mock
    FullTextRetrievalService fullTextRetrievalService;

    @Test
    void retrievesFromBothPathsAndFusesCandidatesWithConfiguredLimit() {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setMaxTopK(2);
        HybridRetrievalService service = new HybridRetrievalService(
                semanticRetrievalService,
                fullTextRetrievalService,
                new RrfFusionService(properties),
                properties
        );
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID sharedChildId = UUID.randomUUID();
        UUID vectorOnlyId = UUID.randomUUID();
        UUID fullTextOnlyId = UUID.randomUUID();
        List<UUID> documentIds = List.of(documentId);

        when(semanticRetrievalService.retrieve("security policy", documentIds, 2)).thenReturn(Flux.just(
                vector(sharedChildId, documentId, parentId, 0, 1),
                vector(vectorOnlyId, documentId, parentId, 1, 2)
        ));
        when(fullTextRetrievalService.retrieve("security policy", documentIds, 2)).thenReturn(Flux.just(
                fullText(sharedChildId, documentId, parentId, 0, 1),
                fullText(fullTextOnlyId, documentId, parentId, 2, 2)
        ));

        StepVerifier.create(service.retrieve("  security policy  ", documentIds, 99))
                .assertNext(result -> {
                    assertThat(result.query()).isEqualTo("security policy");
                    assertThat(result.vectorCandidates()).hasSize(2);
                    assertThat(result.fullTextCandidates()).hasSize(2);
                    assertThat(result.fusedCandidates()).hasSize(2);
                    assertThat(result.fusedCandidates().get(0).childChunkId()).isEqualTo(sharedChildId);
                    assertThat(result.fusedCandidates().get(0).source()).isEqualTo(RetrievalSource.BOTH);
                })
                .verifyComplete();

        verify(semanticRetrievalService).retrieve("security policy", documentIds, 2);
        verify(fullTextRetrievalService).retrieve("security policy", documentIds, 2);
    }

    @Test
    void rejectsBlankQuery() {
        RetrievalProperties properties = new RetrievalProperties();
        HybridRetrievalService service = new HybridRetrievalService(
                semanticRetrievalService,
                fullTextRetrievalService,
                new RrfFusionService(properties),
                properties
        );

        StepVerifier.create(service.retrieve("   ", List.of(), 5))
                .expectError(BadRequestException.class)
                .verify();
    }

    @Test
    void rejectsInvalidTopK() {
        RetrievalProperties properties = new RetrievalProperties();
        HybridRetrievalService service = new HybridRetrievalService(
                semanticRetrievalService,
                fullTextRetrievalService,
                new RrfFusionService(properties),
                properties
        );

        StepVerifier.create(service.retrieve("security policy", List.of(), 0))
                .expectErrorMatches(error -> error instanceof BadRequestException
                        && error.getMessage().contains("topK"))
                .verify();
    }

    @Test
    void returnsEmptyCandidateListsWhenBothRetrievalPathsAreEmpty() {
        RetrievalProperties properties = new RetrievalProperties();
        HybridRetrievalService service = new HybridRetrievalService(
                semanticRetrievalService,
                fullTextRetrievalService,
                new RrfFusionService(properties),
                properties
        );

        when(semanticRetrievalService.retrieve("security policy", List.of(), 5)).thenReturn(Flux.empty());
        when(fullTextRetrievalService.retrieve("security policy", List.of(), 5)).thenReturn(Flux.empty());

        StepVerifier.create(service.retrieve("security policy", List.of(), 5))
                .assertNext(result -> {
                    assertThat(result.vectorCandidates()).isEmpty();
                    assertThat(result.fullTextCandidates()).isEmpty();
                    assertThat(result.fusedCandidates()).isEmpty();
                })
                .verifyComplete();
    }

    private SemanticRetrievalCandidate vector(
            UUID childId,
            UUID documentId,
            UUID parentId,
            int chunkIndex,
            int rank
    ) {
        return new SemanticRetrievalCandidate(
                childId,
                documentId,
                parentId,
                chunkIndex,
                "vector preview " + chunkIndex,
                rank,
                0.10d * rank
        );
    }

    private FullTextRetrievalCandidate fullText(
            UUID childId,
            UUID documentId,
            UUID parentId,
            int chunkIndex,
            int rank
    ) {
        return new FullTextRetrievalCandidate(
                childId,
                documentId,
                parentId,
                chunkIndex,
                "full-text preview " + chunkIndex,
                rank,
                1.0d / rank
        );
    }
}
