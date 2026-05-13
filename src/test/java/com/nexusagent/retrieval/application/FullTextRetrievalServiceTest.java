package com.nexusagent.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.retrieval.domain.FullTextSearchResult;
import com.nexusagent.retrieval.repository.FullTextSearchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class FullTextRetrievalServiceTest {

    @Mock
    FullTextSearchRepository fullTextSearchRepository;

    @Test
    void ranksFullTextSearchResults() {
        FullTextRetrievalService service = new FullTextRetrievalService(fullTextSearchRepository);
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        List<UUID> documentIds = List.of(documentId);

        when(fullTextSearchRepository.search("security policy", documentIds, 3)).thenReturn(Flux.just(
                new FullTextSearchResult(
                        childId,
                        documentId,
                        parentId,
                        4,
                        "security policy preview",
                        0.82d
                )
        ));

        StepVerifier.create(service.retrieve("security policy", documentIds, 3).collectList())
                .assertNext(candidates -> {
                    assertThat(candidates).hasSize(1);
                    assertThat(candidates.get(0).childChunkId()).isEqualTo(childId);
                    assertThat(candidates.get(0).fullTextRank()).isEqualTo(1);
                    assertThat(candidates.get(0).fullTextScore()).isEqualTo(0.82d);
                    assertThat(candidates.get(0).previewText()).contains("security policy");
                })
                .verifyComplete();

        verify(fullTextSearchRepository).search("security policy", documentIds, 3);
    }

    @Test
    void preservesRepositoryOrderingAndAssignsOneBasedFullTextRanks() {
        FullTextRetrievalService service = new FullTextRetrievalService(fullTextSearchRepository);
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID firstChildId = UUID.randomUUID();
        UUID secondChildId = UUID.randomUUID();

        when(fullTextSearchRepository.search("security policy", List.of(), 2)).thenReturn(Flux.just(
                new FullTextSearchResult(firstChildId, documentId, parentId, 0, "best keyword hit", 0.90d),
                new FullTextSearchResult(secondChildId, documentId, parentId, 1, "second keyword hit", 0.40d)
        ));

        StepVerifier.create(service.retrieve("security policy", List.of(), 2).collectList())
                .assertNext(candidates -> {
                    assertThat(candidates).extracting(candidate -> candidate.childChunkId())
                            .containsExactly(firstChildId, secondChildId);
                    assertThat(candidates).extracting(candidate -> candidate.fullTextRank())
                            .containsExactly(1, 2);
                    assertThat(candidates).extracting(candidate -> candidate.fullTextScore())
                            .containsExactly(0.90d, 0.40d);
                })
                .verifyComplete();
    }
}
