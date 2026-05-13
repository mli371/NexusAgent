package com.nexusagent.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;

import com.nexusagent.retrieval.domain.FullTextRetrievalCandidate;
import com.nexusagent.retrieval.domain.RetrievalSource;
import com.nexusagent.retrieval.domain.SemanticRetrievalCandidate;
import org.junit.jupiter.api.Test;

class RrfFusionServiceTest {

    private final RetrievalProperties properties = new RetrievalProperties();
    private final RrfFusionService service = new RrfFusionService(properties);

    @Test
    void calculatesReciprocalRankFusionScoreFromRankOnly() {
        assertThat(service.rrfScore(60, 1)).isCloseTo(1.0d / 61.0d, within(0.000001d));
        assertThat(service.rrfScore(60, 10)).isCloseTo(1.0d / 70.0d, within(0.000001d));
    }

    @Test
    void rejectsZeroBasedRanks() {
        assertThatThrownBy(() -> service.rrfScore(60, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
    }

    @Test
    void fusesByRankPositionAndIgnoresRawScores() {
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID firstRankId = UUID.randomUUID();
        UUID secondRankId = UUID.randomUUID();

        var fused = service.fuse(
                List.of(
                        vector(firstRankId, documentId, parentId, 0, 1, 0.99d),
                        vector(secondRankId, documentId, parentId, 1, 2, 0.01d)
                ),
                List.of(),
                10
        );

        assertThat(fused).extracting(candidate -> candidate.childChunkId())
                .containsExactly(firstRankId, secondRankId);
        assertThat(fused.get(0).rrfScore()).isGreaterThan(fused.get(1).rrfScore());
    }

    @Test
    void deduplicatesCandidatesByChildChunkIdAndTracksBothSources() {
        UUID sharedChildId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        List<SemanticRetrievalCandidate> vectorCandidates = List.of(
                vector(sharedChildId, documentId, parentId, 0, 1, 0.12d),
                vector(UUID.randomUUID(), documentId, parentId, 1, 2, 0.24d)
        );
        List<FullTextRetrievalCandidate> fullTextCandidates = List.of(
                fullText(UUID.randomUUID(), documentId, parentId, 2, 1, 0.91d),
                fullText(sharedChildId, documentId, parentId, 0, 2, 0.73d)
        );

        var fused = service.fuse(vectorCandidates, fullTextCandidates, 10);

        assertThat(fused).hasSize(3);
        assertThat(fused)
                .filteredOn(candidate -> candidate.childChunkId().equals(sharedChildId))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.source()).isEqualTo(RetrievalSource.BOTH);
                    assertThat(candidate.vectorRank()).isEqualTo(1);
                    assertThat(candidate.fullTextRank()).isEqualTo(2);
                    assertThat(candidate.vectorDistance()).isEqualTo(0.12d);
                    assertThat(candidate.fullTextScore()).isEqualTo(0.73d);
                    assertThat(candidate.rrfScore())
                            .isCloseTo((1.0d / 61.0d) + (1.0d / 62.0d), within(0.000001d));
                });
    }

    @Test
    void tracksVectorOnlyAndFullTextOnlySources() {
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID vectorOnlyId = UUID.randomUUID();
        UUID fullTextOnlyId = UUID.randomUUID();

        var fused = service.fuse(
                List.of(vector(vectorOnlyId, documentId, parentId, 0, 1, 0.1d)),
                List.of(fullText(fullTextOnlyId, documentId, parentId, 1, 1, 0.8d)),
                10
        );

        assertThat(fused)
                .filteredOn(candidate -> candidate.childChunkId().equals(vectorOnlyId))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.source()).isEqualTo(RetrievalSource.VECTOR);
                    assertThat(candidate.vectorRank()).isEqualTo(1);
                    assertThat(candidate.fullTextRank()).isNull();
                });
        assertThat(fused)
                .filteredOn(candidate -> candidate.childChunkId().equals(fullTextOnlyId))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.source()).isEqualTo(RetrievalSource.FULL_TEXT);
                    assertThat(candidate.vectorRank()).isNull();
                    assertThat(candidate.fullTextRank()).isEqualTo(1);
                });
    }

    private SemanticRetrievalCandidate vector(
            UUID childId,
            UUID documentId,
            UUID parentId,
            int chunkIndex,
            int rank,
            double distance
    ) {
        return new SemanticRetrievalCandidate(
                childId,
                documentId,
                parentId,
                chunkIndex,
                "vector preview " + chunkIndex,
                rank,
                distance
        );
    }

    private FullTextRetrievalCandidate fullText(
            UUID childId,
            UUID documentId,
            UUID parentId,
            int chunkIndex,
            int rank,
            double score
    ) {
        return new FullTextRetrievalCandidate(
                childId,
                documentId,
                parentId,
                chunkIndex,
                "full-text preview " + chunkIndex,
                rank,
                score
        );
    }
}
