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
        ContextBuilder builder = builder(20);
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

        StepVerifier.create(builder.build("security policy", List.of(), 5, 20))
                .assertNext(context -> {
                    assertThat(context.expandedParentContexts()).hasSize(1);
                    assertThat(context.expandedParentContexts().get(0).text()).hasSize(20);
                    assertThat(context.expandedParentContexts().get(0).truncated()).isTrue();
                    assertThat(context.debugMetadata().usedBudgetChars()).isEqualTo(20);
                    assertThat(context.finalContextText()).contains("[truncated]");
                })
                .verifyComplete();
    }

    @Test
    void trimsAroundMatchedChildChunkInsteadOfParentStart() {
        ContextBuilder builder = builder(28);
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

        StepVerifier.create(builder.build("security policy", List.of(), 5, 28))
                .assertNext(context -> {
                    assertThat(context.expandedParentContexts()).hasSize(1);
                    assertThat(context.expandedParentContexts().get(0).text())
                            .contains("SECURITY-POLICY-EVIDENCE")
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

    @Test void fiveCompleteChildCitationsSurviveAndParentBudgetIsShared() {
        var rows = java.util.stream.IntStream.range(0, 5).mapToObj(i -> ContextBudgetAllocatorTest.candidate(i + 1,
                "apple-" + (2022 + i) + ".md", "a".repeat(3000), 1200, 1600, 100)).toList();
        var ranked = rows.stream().map(ExpandedCandidateContext::rerankedCandidate).toList();
        var fused = ranked.stream().map(RerankedCandidate::candidate).toList();
        when(hybridRetrievalService.retrieve("compare years", List.of(), 5, RequestContext.defaults()))
                .thenReturn(Mono.just(result("compare years", fused)));
        when(reranker.rerank("compare years", fused)).thenReturn(ranked);
        when(parentContextExpansionService.expand(ranked, RequestContext.defaults())).thenReturn(Mono.just(rows));
        StepVerifier.create(builder(4000).build("compare years", List.of(), 5, 4000)).assertNext(context -> {
            assertThat(context.selectedChildChunks()).hasSize(5);
            assertThat(context.citations()).hasSize(5);
            assertThat(context.debugMetadata().usedBudgetChars()).isEqualTo(4000);
            assertThat(context.debugMetadata().reservedChildChars()).isEqualTo(2000);
            assertThat(context.debugMetadata().skippedBudgetCount()).isZero();
            assertThat(context.debugMetadata().trimmedParentCount()).isEqualTo(5);
            assertThat(context.debugMetadata().allocationStrategy()).isEqualTo("child-first-v1");
            for (int i = 0; i < 5; i++) {
                var parent = context.expandedParentContexts().get(i);
                var child = context.selectedChildChunks().get(i);
                assertThat(parent.includedChars()).isEqualTo(800);
                assertThat(parent.charStart()).isLessThanOrEqualTo(child.charStart());
                assertThat(parent.charEnd()).isGreaterThanOrEqualTo(child.charEnd());
                assertThat(context.citations().get(i).childChunkId()).isEqualTo(child.childChunkId());
            }
            assertThat(context.finalContextText()).contains("apple-2025.md", "[C5]");
        }).verifyComplete();
    }

    @Test void budgetSmallerThanChildReturnsEmptyEvidenceAndExplicitReason() {
        var row = ContextBudgetAllocatorTest.candidate(1, "too-large.md", "a".repeat(100), 20, 70, 0);
        var ranked = List.of(row.rerankedCandidate());
        var fused = ranked.stream().map(RerankedCandidate::candidate).toList();
        when(hybridRetrievalService.retrieve("policy", List.of(), 5, RequestContext.defaults())).thenReturn(Mono.just(result("policy", fused)));
        when(reranker.rerank("policy", fused)).thenReturn(ranked);
        when(parentContextExpansionService.expand(ranked, RequestContext.defaults())).thenReturn(Mono.just(List.of(row)));
        StepVerifier.create(builder(10).build("policy", List.of(), 5, 10)).assertNext(context -> {
            assertThat(context.finalContextText()).isEmpty();
            assertThat(context.citations()).isEmpty();
            assertThat(context.selectedChildChunks()).isEmpty();
            assertThat(context.debugMetadata().skippedBudgetCount()).isEqualTo(1);
            assertThat(context.debugMetadata().allocations().get(0).status().name()).isEqualTo("CHILD_EXCEEDS_REMAINING_BUDGET");
        }).verifyComplete();
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
