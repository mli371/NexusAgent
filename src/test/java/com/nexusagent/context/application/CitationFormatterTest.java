package com.nexusagent.context.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.context.domain.ExpandedCandidateContext;
import com.nexusagent.context.domain.ExpandedParentContext;
import com.nexusagent.context.domain.RerankScoreBreakdown;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.junit.jupiter.api.Test;

class CitationFormatterTest {

    private final CitationFormatter formatter = new CitationFormatter();

    @Test
    void formatsCitationMetadataAndContextMarkers() {
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        ExpandedCandidateContext expanded = expanded(documentId, parentId, childId, now);

        var citation = formatter.citation(1, expanded);
        var contextText = formatter.formatContext(
                List.of(new ExpandedParentContext(
                        parentId,
                        documentId,
                        "notes.txt",
                        0,
                        0,
                        35,
                        "Security policy parent context",
                        false,
                        30,
                        List.of(childId)
                )),
                List.of(citation)
        );

        assertThat(citation.citationMarker()).isEqualTo("[C1]");
        assertThat(citation.documentId()).isEqualTo(documentId);
        assertThat(citation.parentChunkId()).isEqualTo(parentId);
        assertThat(citation.childChunkId()).isEqualTo(childId);
        assertThat(contextText).contains("[C1] notes.txt").contains("Security policy parent context");
    }

    private ExpandedCandidateContext expanded(UUID documentId, UUID parentId, UUID childId, OffsetDateTime now) {
        DocumentMetadata document = DocumentMetadata.stored(
                documentId,
                "notes.txt",
                "text/plain",
                100,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
                "bucket",
                "documents/%s/notes.txt".formatted(documentId),
                now
        );
        ParentChunk parent = new ParentChunk(parentId, documentId, 0, "Security policy parent context", 0, 30, 4, now);
        ChildChunk child = new ChildChunk(childId, documentId, parentId, 0, "Security policy", 0, 15, 2, now);
        RerankedCandidate reranked = new RerankedCandidate(
                new FusedRetrievalCandidate(
                        childId,
                        documentId,
                        parentId,
                        0,
                        "Security policy",
                        RetrievalSource.BOTH,
                        1,
                        0.1d,
                        1,
                        0.8d,
                        0.03d
                ),
                1,
                1,
                3.0d,
                new RerankScoreBreakdown(3.0d, 0.0d, 0.2d, 0.0d),
                "test"
        );
        return new ExpandedCandidateContext(reranked, document, child, parent);
    }
}
