package com.nexusagent.retrieval.api;

import java.util.List;
import java.util.UUID;

import com.nexusagent.retrieval.domain.FullTextRetrievalCandidate;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.HybridRetrievalResult;
import com.nexusagent.retrieval.domain.RetrievalSource;
import com.nexusagent.retrieval.domain.SemanticRetrievalCandidate;

public record RetrievalDebugResponse(
        String query,
        List<CandidateResponse> vectorCandidates,
        List<CandidateResponse> fullTextCandidates,
        List<CandidateResponse> fusedCandidates
) {

    public static RetrievalDebugResponse from(HybridRetrievalResult result) {
        return new RetrievalDebugResponse(
                result.query(),
                result.vectorCandidates().stream().map(CandidateResponse::fromVector).toList(),
                result.fullTextCandidates().stream().map(CandidateResponse::fromFullText).toList(),
                result.fusedCandidates().stream().map(CandidateResponse::fromFused).toList()
        );
    }

    public record CandidateResponse(
            UUID childChunkId,
            UUID parentChunkId,
            UUID documentId,
            int chunkIndex,
            String previewText,
            RetrievalSource source,
            Integer vectorRank,
            Double vectorDistance,
            Integer fullTextRank,
            Double fullTextScore,
            Double rrfScore
    ) {

        static CandidateResponse fromVector(SemanticRetrievalCandidate candidate) {
            return new CandidateResponse(
                    candidate.childChunkId(),
                    candidate.parentChunkId(),
                    candidate.documentId(),
                    candidate.chunkIndex(),
                    candidate.previewText(),
                    RetrievalSource.VECTOR,
                    candidate.vectorRank(),
                    candidate.vectorDistance(),
                    null,
                    null,
                    null
            );
        }

        static CandidateResponse fromFullText(FullTextRetrievalCandidate candidate) {
            return new CandidateResponse(
                    candidate.childChunkId(),
                    candidate.parentChunkId(),
                    candidate.documentId(),
                    candidate.chunkIndex(),
                    candidate.previewText(),
                    RetrievalSource.FULL_TEXT,
                    null,
                    null,
                    candidate.fullTextRank(),
                    candidate.fullTextScore(),
                    null
            );
        }

        static CandidateResponse fromFused(FusedRetrievalCandidate candidate) {
            return new CandidateResponse(
                    candidate.childChunkId(),
                    candidate.parentChunkId(),
                    candidate.documentId(),
                    candidate.chunkIndex(),
                    candidate.previewText(),
                    candidate.source(),
                    candidate.vectorRank(),
                    candidate.vectorDistance(),
                    candidate.fullTextRank(),
                    candidate.fullTextScore(),
                    candidate.rrfScore()
            );
        }
    }
}
