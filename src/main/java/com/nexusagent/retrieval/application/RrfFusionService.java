package com.nexusagent.retrieval.application;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nexusagent.retrieval.domain.FullTextRetrievalCandidate;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.RetrievalSource;
import com.nexusagent.retrieval.domain.SemanticRetrievalCandidate;
import org.springframework.stereotype.Service;

@Service
public class RrfFusionService {

    private final RetrievalProperties retrievalProperties;

    public RrfFusionService(RetrievalProperties retrievalProperties) {
        this.retrievalProperties = retrievalProperties;
    }

    public List<FusedRetrievalCandidate> fuse(
            List<SemanticRetrievalCandidate> vectorCandidates,
            List<FullTextRetrievalCandidate> fullTextCandidates,
            int limit
    ) {
        Map<UUID, MutableCandidate> candidates = new LinkedHashMap<>();
        int k = retrievalProperties.getRrfK();

        for (SemanticRetrievalCandidate vectorCandidate : vectorCandidates) {
            MutableCandidate candidate = candidates.computeIfAbsent(
                    vectorCandidate.childChunkId(),
                    ignored -> MutableCandidate.fromVector(vectorCandidate)
            );
            if (candidate.vectorRank == null) {
                candidate.vectorRank = vectorCandidate.vectorRank();
                candidate.vectorDistance = vectorCandidate.vectorDistance();
                candidate.rrfScore += rrfScore(k, vectorCandidate.vectorRank());
            }
        }

        for (FullTextRetrievalCandidate fullTextCandidate : fullTextCandidates) {
            MutableCandidate candidate = candidates.computeIfAbsent(
                    fullTextCandidate.childChunkId(),
                    ignored -> MutableCandidate.fromFullText(fullTextCandidate)
            );
            if (candidate.fullTextRank == null) {
                candidate.fullTextRank = fullTextCandidate.fullTextRank();
                candidate.fullTextScore = fullTextCandidate.fullTextScore();
                candidate.rrfScore += rrfScore(k, fullTextCandidate.fullTextRank());
            }
        }

        return candidates.values().stream()
                .map(MutableCandidate::toFused)
                .sorted(Comparator.comparingDouble(FusedRetrievalCandidate::rrfScore).reversed()
                        .thenComparing(FusedRetrievalCandidate::chunkIndex))
                .limit(limit)
                .toList();
    }

    double rrfScore(int k, int rank) {
        if (rank < 1) {
            throw new IllegalArgumentException("RRF rank must be 1-based and greater than 0");
        }
        return 1.0d / (k + rank);
    }

    private static class MutableCandidate {

        private final UUID childChunkId;
        private final UUID documentId;
        private final UUID parentChunkId;
        private final int chunkIndex;
        private final String previewText;
        private Integer vectorRank;
        private Double vectorDistance;
        private Integer fullTextRank;
        private Double fullTextScore;
        private double rrfScore;

        private MutableCandidate(
                UUID childChunkId,
                UUID documentId,
                UUID parentChunkId,
                int chunkIndex,
                String previewText
        ) {
            this.childChunkId = childChunkId;
            this.documentId = documentId;
            this.parentChunkId = parentChunkId;
            this.chunkIndex = chunkIndex;
            this.previewText = previewText;
        }

        private static MutableCandidate fromVector(SemanticRetrievalCandidate candidate) {
            return new MutableCandidate(
                    candidate.childChunkId(),
                    candidate.documentId(),
                    candidate.parentChunkId(),
                    candidate.chunkIndex(),
                    candidate.previewText()
            );
        }

        private static MutableCandidate fromFullText(FullTextRetrievalCandidate candidate) {
            return new MutableCandidate(
                    candidate.childChunkId(),
                    candidate.documentId(),
                    candidate.parentChunkId(),
                    candidate.chunkIndex(),
                    candidate.previewText()
            );
        }

        private FusedRetrievalCandidate toFused() {
            return new FusedRetrievalCandidate(
                    childChunkId,
                    documentId,
                    parentChunkId,
                    chunkIndex,
                    previewText,
                    source(),
                    vectorRank,
                    vectorDistance,
                    fullTextRank,
                    fullTextScore,
                    rrfScore
            );
        }

        private RetrievalSource source() {
            if (vectorRank != null && fullTextRank != null) {
                return RetrievalSource.BOTH;
            }
            if (vectorRank != null) {
                return RetrievalSource.VECTOR;
            }
            return RetrievalSource.FULL_TEXT;
        }
    }
}
