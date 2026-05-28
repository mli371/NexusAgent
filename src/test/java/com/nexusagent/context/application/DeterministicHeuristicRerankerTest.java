package com.nexusagent.context.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.junit.jupiter.api.Test;

class DeterministicHeuristicRerankerTest {

    private final DeterministicHeuristicReranker reranker = new DeterministicHeuristicReranker();

    @Test
    void ordersCandidatesByDeterministicHeuristicScore() {
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        FusedRetrievalCandidate weakerKeywordCandidate = candidate(
                UUID.randomUUID(),
                documentId,
                parentId,
                0,
                "cafeteria menu",
                RetrievalSource.VECTOR,
                0.020d
        );
        FusedRetrievalCandidate strongerKeywordCandidate = candidate(
                UUID.randomUUID(),
                documentId,
                UUID.randomUUID(),
                1,
                "security policy access controls",
                RetrievalSource.VECTOR,
                0.019d
        );

        var reranked = reranker.rerank("security policy", List.of(weakerKeywordCandidate, strongerKeywordCandidate));

        assertThat(reranked).extracting(result -> result.candidate().childChunkId())
                .containsExactly(strongerKeywordCandidate.childChunkId(), weakerKeywordCandidate.childChunkId());
        assertThat(reranked.get(0).scoreBreakdown().keywordOverlapScore()).isPositive();
    }

    @Test
    void sourceBothContributesMoreThanSingleSource() {
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        FusedRetrievalCandidate vectorOnly = candidate(
                UUID.randomUUID(),
                documentId,
                parentId,
                0,
                "security policy",
                RetrievalSource.VECTOR,
                0.020d
        );
        FusedRetrievalCandidate both = candidate(
                UUID.randomUUID(),
                documentId,
                UUID.randomUUID(),
                1,
                "security policy",
                RetrievalSource.BOTH,
                0.020d
        );

        var reranked = reranker.rerank("security policy", List.of(vectorOnly, both));

        assertThat(reranked.get(0).candidate().childChunkId()).isEqualTo(both.childChunkId());
        assertThat(reranked.get(0).scoreBreakdown().sourceBoost())
                .isGreaterThan(reranked.get(1).scoreBreakdown().sourceBoost());
    }

    @Test
    void parentDiversityCanMoveRepeatedParentBelowDifferentParent() {
        UUID documentId = UUID.randomUUID();
        UUID repeatedParentId = UUID.randomUUID();
        FusedRetrievalCandidate firstParentHit = candidate(
                UUID.randomUUID(),
                documentId,
                repeatedParentId,
                0,
                "security policy",
                RetrievalSource.BOTH,
                0.020d
        );
        FusedRetrievalCandidate repeatedParentHit = candidate(
                UUID.randomUUID(),
                documentId,
                repeatedParentId,
                1,
                "security policy",
                RetrievalSource.BOTH,
                0.019d
        );
        FusedRetrievalCandidate differentParentHit = candidate(
                UUID.randomUUID(),
                documentId,
                UUID.randomUUID(),
                2,
                "security policy",
                RetrievalSource.BOTH,
                0.018d
        );

        var reranked = reranker.rerank(
                "security policy",
                List.of(firstParentHit, repeatedParentHit, differentParentHit)
        );

        assertThat(reranked).extracting(result -> result.candidate().childChunkId())
                .containsExactly(
                        firstParentHit.childChunkId(),
                        differentParentHit.childChunkId(),
                        repeatedParentHit.childChunkId()
                );
        assertThat(reranked.get(2).scoreBreakdown().diversityPenalty()).isPositive();
    }

    private FusedRetrievalCandidate candidate(
            UUID childChunkId,
            UUID documentId,
            UUID parentChunkId,
            int chunkIndex,
            String previewText,
            RetrievalSource source,
            double rrfScore
    ) {
        return new FusedRetrievalCandidate(
                childChunkId,
                documentId,
                parentChunkId,
                chunkIndex,
                previewText,
                source,
                source == RetrievalSource.FULL_TEXT ? null : 1,
                source == RetrievalSource.FULL_TEXT ? null : 0.12d,
                source == RetrievalSource.VECTOR ? null : 1,
                source == RetrievalSource.VECTOR ? null : 0.83d,
                rrfScore
        );
    }
}
