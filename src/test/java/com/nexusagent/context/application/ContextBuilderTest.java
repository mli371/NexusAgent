package com.nexusagent.context.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.context.domain.ExpandedCandidateContext;
import com.nexusagent.context.domain.RerankScoreBreakdown;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.retrieval.application.HybridRetrievalService;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.HybridRetrievalResult;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ContextBuilderTest {

    @Mock
    HybridRetrievalService hybridRetrievalService;

    @Mock
    Reranker reranker;

    @Mock
    ParentContextExpansionService parentContextExpansionService;

    @Test
    void deduplicatesParentContextsByParentChunkId() {
        ContextBuilder builder = builder(1000);
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID firstChildId = UUID.randomUUID();
        UUID secondChildId = UUID.randomUUID();
        RerankedCandidate first = reranked(documentId, parentId, firstChildId, 0, 1);
        RerankedCandidate second = reranked(documentId, parentId, secondChildId, 1, 2);

        when(hybridRetrievalService.retrieve("security policy", List.of(), 5, RequestContext.defaults()))
                .thenReturn(Mono.just(result("security policy", List.of(first.candidate(), second.candidate()))));
        when(reranker.rerank("security policy", List.of(first.candidate(), second.candidate())))
                .thenReturn(List.of(first, second));
        when(parentContextExpansionService.expand(List.of(first, second), RequestContext.defaults()))
                .thenReturn(Mono.just(List.of(
                        expanded(first, "Parent context with security policy."),
                        expanded(second, "Parent context with security policy.")
                )));

        StepVerifier.create(builder.build("security policy", List.of(), 5, 1000))
                .assertNext(context -> {
                    assertThat(context.selectedChildChunks()).hasSize(1);
                    assertThat(context.expandedParentContexts()).hasSize(1);
                    assertThat(context.debugMetadata().skippedDuplicateParentCount()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void trimsParentContextToCharacterBudget() {
        ContextBuilder builder = builder(12);
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        RerankedCandidate candidate = reranked(documentId, parentId, childId, 0, 1);

        when(hybridRetrievalService.retrieve("security policy", List.of(), 5, RequestContext.defaults()))
                .thenReturn(Mono.just(result("security policy", List.of(candidate.candidate()))));
        when(reranker.rerank("security policy", List.of(candidate.candidate())))
                .thenReturn(List.of(candidate));
        when(parentContextExpansionService.expand(List.of(candidate), RequestContext.defaults()))
                .thenReturn(Mono.just(List.of(expanded(candidate, "Security policy parent context"))));

        StepVerifier.create(builder.build("security policy", List.of(), 5, 12))
                .assertNext(context -> {
                    assertThat(context.expandedParentContexts()).hasSize(1);
                    assertThat(context.expandedParentContexts().get(0).text()).hasSize(12);
                    assertThat(context.expandedParentContexts().get(0).truncated()).isTrue();
                    assertThat(context.debugMetadata().usedBudgetChars()).isEqualTo(12);
                    assertThat(context.finalContextText()).contains("[truncated]");
                })
                .verifyComplete();
    }

    @Test
    void trimsAroundMatchedChildChunkInsteadOfParentStart() {
        ContextBuilder builder = builder(20);
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        RerankedCandidate candidate = reranked(documentId, parentId, childId, 0, 1);
        String parentText = "prefix text that should be trimmed SECURITY-POLICY-EVIDENCE trailing text";
        int childStart = parentText.indexOf("SECURITY");
        int childEnd = parentText.indexOf(" trailing");

        when(hybridRetrievalService.retrieve("security policy", List.of(), 5, RequestContext.defaults()))
                .thenReturn(Mono.just(result("security policy", List.of(candidate.candidate()))));
        when(reranker.rerank("security policy", List.of(candidate.candidate())))
                .thenReturn(List.of(candidate));
        when(parentContextExpansionService.expand(List.of(candidate), RequestContext.defaults()))
                .thenReturn(Mono.just(List.of(expanded(candidate, parentText, childStart, childEnd))));

        StepVerifier.create(builder.build("security policy", List.of(), 5, 20))
                .assertNext(context -> {
                    assertThat(context.expandedParentContexts()).hasSize(1);
                    assertThat(context.expandedParentContexts().get(0).text())
                            .contains("SECURITY")
                            .doesNotStartWith("prefix");
                    assertThat(context.expandedParentContexts().get(0).charStart()).isGreaterThan(0);
                    assertThat(context.finalContextText()).contains("SECURITY");
                })
                .verifyComplete();
    }

    @Test
    void citationsReferenceSelectedChildAndExpandedParentContexts() {
        ContextBuilder builder = builder(1000);
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        RerankedCandidate candidate = reranked(documentId, parentId, childId, 3, 1);

        when(hybridRetrievalService.retrieve("security policy", List.of(), 5, RequestContext.defaults()))
                .thenReturn(Mono.just(result("security policy", List.of(candidate.candidate()))));
        when(reranker.rerank("security policy", List.of(candidate.candidate())))
                .thenReturn(List.of(candidate));
        when(parentContextExpansionService.expand(List.of(candidate), RequestContext.defaults()))
                .thenReturn(Mono.just(List.of(expanded(candidate, "Parent context with security policy."))));

        StepVerifier.create(builder.build("security policy", List.of(), 5, 1000))
                .assertNext(context -> {
                    assertThat(context.citations()).hasSize(1);
                    assertThat(context.selectedChildChunks()).hasSize(1);
                    assertThat(context.expandedParentContexts()).hasSize(1);

                    var citation = context.citations().get(0);
                    var selectedChild = context.selectedChildChunks().get(0);
                    var parentContext = context.expandedParentContexts().get(0);

                    assertThat(citation.childChunkId()).isEqualTo(selectedChild.childChunkId());
                    assertThat(citation.parentChunkId()).isEqualTo(parentContext.parentChunkId());
                    assertThat(citation.documentId()).isEqualTo(documentId);
                    assertThat(citation.originalFilename()).isEqualTo("notes.txt");
                    assertThat(citation.chunkIndex()).isEqualTo(3);
                    assertThat(citation.charStart()).isEqualTo(selectedChild.charStart());
                    assertThat(citation.charEnd()).isEqualTo(selectedChild.charEnd());
                })
                .verifyComplete();
    }

    @Test
    void rejectsInvalidContextBudget() {
        ContextBuilder builder = builder(1000);

        StepVerifier.create(builder.build("security policy", List.of(), 5, 0))
                .expectError(BadRequestException.class)
                .verify();
    }

    private ContextBuilder builder(int maxBudgetChars) {
        ContextProperties properties = new ContextProperties();
        properties.setDefaultBudgetChars(1000);
        properties.setMaxBudgetChars(maxBudgetChars);
        return new ContextBuilder(
                hybridRetrievalService,
                reranker,
                parentContextExpansionService,
                new CitationFormatter(),
                properties
        );
    }

    private HybridRetrievalResult result(String query, List<FusedRetrievalCandidate> fusedCandidates) {
        return new HybridRetrievalResult(query, List.of(), List.of(), fusedCandidates);
    }

    private RerankedCandidate reranked(UUID documentId, UUID parentId, UUID childId, int chunkIndex, int rank) {
        return new RerankedCandidate(
                new FusedRetrievalCandidate(
                        childId,
                        documentId,
                        parentId,
                        chunkIndex,
                        "security policy preview",
                        RetrievalSource.BOTH,
                        1,
                        0.1d,
                        1,
                        0.8d,
                        0.03d
                ),
                rank,
                rank,
                3.0d - rank,
                new RerankScoreBreakdown(3.0d, 0.0d, 0.2d, 0.0d),
                "test"
        );
    }

    private ExpandedCandidateContext expanded(RerankedCandidate candidate, String parentText) {
        return expanded(candidate, parentText, 0, 15);
    }

    private ExpandedCandidateContext expanded(
            RerankedCandidate candidate,
            String parentText,
            int childStart,
            int childEnd
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        UUID documentId = candidate.candidate().documentId();
        UUID parentId = candidate.candidate().parentChunkId();
        UUID childId = candidate.candidate().childChunkId();
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
        ParentChunk parent = new ParentChunk(parentId, documentId, 0, parentText, 0, parentText.length(), 4, now);
        ChildChunk child = new ChildChunk(
                childId,
                documentId,
                parentId,
                candidate.candidate().chunkIndex(),
                parentText.substring(childStart, childEnd),
                childStart,
                childEnd,
                2,
                now
        );
        return new ExpandedCandidateContext(candidate, document, child, parent);
    }
}
