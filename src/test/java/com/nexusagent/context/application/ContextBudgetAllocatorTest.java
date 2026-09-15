package com.nexusagent.context.application;

import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.context.domain.ContextAllocation.Status;
import com.nexusagent.context.domain.ExpandedCandidateContext;
import com.nexusagent.context.domain.RerankScoreBreakdown;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.junit.jupiter.api.Test;

class ContextBudgetAllocatorTest {
    private final ContextBudgetAllocator allocator = new ContextBudgetAllocator();

    @Test void fiveYearsAllSurvive4000CharsDespiteLargeLeadingParents() {
        var rows = IntStream.range(0, 5).mapToObj(i -> candidate(i + 1, "apple-" + (2022 + i) + ".md",
                "p".repeat(3000), 1000, 1400, i * 4000)).toList();
        var plan = allocator.select(rows, 4000);
        assertThat(plan.reservedChars()).isEqualTo(2000);
        var windows = allocator.expand(plan);
        assertThat(windows).hasSize(5).allSatisfy(w -> assertThat(w.end() - w.start()).isEqualTo(800));
        assertThat(allocator.diagnostics(plan, windows)).allSatisfy(a -> {
            assertThat(a.status()).isEqualTo(Status.INCLUDED);
            assertThat(a.allocatedChars()).isEqualTo(800);
            assertThat(a.parentTruncated()).isTrue();
        });
        assertEvidence(plan, windows);
    }

    @Test void unusedShortParentQuotaIsRedistributedEqually() {
        var rows = List.of(candidate(1, "short", "a".repeat(450), 0, 400, 0),
                candidate(2, "long", "b".repeat(2000), 0, 400, 0), candidate(3, "long", "c".repeat(2000), 1500, 1900, 0));
        var plan = allocator.select(rows, 2000);
        var windows = allocator.expand(plan);
        assertThat(windows).extracting(w -> w.end() - w.start()).containsExactly(450, 775, 775);
        assertEvidence(plan, windows);
    }

    @Test void oversizedChildIsExplicitlyExcludedButLaterCompleteChildCanFit() {
        var rows = List.of(candidate(1, "large", "a".repeat(200), 0, 100, 0),
                candidate(2, "small", "b".repeat(100), 20, 30, 1000), candidate(3, "medium", "c".repeat(100), 0, 20, 0));
        var plan = allocator.select(rows, 25);
        assertThat(plan.decisions()).extracting(ContextBudgetAllocator.Decision::status)
                .containsExactly(Status.CHILD_EXCEEDS_REMAINING_BUDGET, Status.INCLUDED, Status.CHILD_EXCEEDS_REMAINING_BUDGET);
        var windows = allocator.expand(plan);
        assertThat(windows).hasSize(1);
        assertEvidence(plan, windows);
        assertThat(allocator.diagnostics(plan, windows).get(0).allocatedChars()).isZero();
    }

    @Test void emptyOrTooSmallBudgetProducesNoPartialEvidence() {
        assertThat(allocator.expand(allocator.select(List.of(), 10))).isEmpty();
        var plan = allocator.select(List.of(candidate(1, "x", "abcdef", 1, 5, 0)), 3);
        assertThat(plan.reservedChars()).isZero();
        assertThat(allocator.expand(plan)).isEmpty();
        assertThat(allocator.diagnostics(plan, List.of()).get(0).status()).isEqualTo(Status.CHILD_EXCEEDS_REMAINING_BUDGET);
        assertThatThrownBy(() -> allocator.select(List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateParentKeepsOnlyFirstRepresentativeEvenWhenItDidNotFit() {
        var first = candidate(1, "duplicate", "abcdefghij", 0, 8, 0);
        var child = new ChildChunk(UUID.randomUUID(), first.document().id(), first.parentChunk().id(), 1, "bc", 1, 3, 1, OffsetDateTime.now());
        var fused = new FusedRetrievalCandidate(child.id(), child.documentId(), child.parentChunkId(), 1, "bc",
                RetrievalSource.VECTOR, 2, 0.2, null, null, 0.01);
        var second = new ExpandedCandidateContext(new RerankedCandidate(fused, 2, 2, 1,
                new RerankScoreBreakdown(1, 0, 0, 0), "fixture"), first.document(), child, first.parentChunk());
        var plan = allocator.select(List.of(first, second), 5);
        assertThat(plan.decisions()).extracting(ContextBudgetAllocator.Decision::status)
                .containsExactly(Status.CHILD_EXCEEDS_REMAINING_BUDGET, Status.DUPLICATE_PARENT);
        var full = allocator.select(List.of(first, second), 20);
        assertThat(allocator.expand(full)).hasSize(1);
        assertThat(allocator.diagnostics(full, allocator.expand(full))).extracting(a -> a.status())
                .containsExactly(Status.INCLUDED, Status.DUPLICATE_PARENT);
    }

    @Test void everyBudgetKeepsCompleteChildrenAtBeginningMiddleAndEnd() {
        var rows = List.of(candidate(1, "begin", "x".repeat(100), 0, 10, 100),
                candidate(2, "middle", "y".repeat(140), 60, 80, 500), candidate(3, "end", "z".repeat(100), 80, 100, 900));
        for (int budget = 1; budget <= 400; budget++) {
            var plan = allocator.select(rows, budget);
            var result = allocator.expand(plan);
            assertEvidence(plan, result);
            assertThat(allocator.expand(plan)).isEqualTo(result);
            if (budget >= 50) { assertThat(result).hasSize(3); }
        }
    }

    @Test void unicodeBoundariesDoNotSplitSurrogatePairs() {
        String text = "a\uD83D\uDE00bc\uD83D\uDE00d";
        for (int budget = 2; budget <= 12; budget++) {
            var plan = allocator.select(List.of(candidate(1, "unicode", text, 3, 5, 17)), budget);
            var windows = allocator.expand(plan);
            assertEvidence(plan, windows);
            String snippet = text.substring(windows.get(0).start(), windows.get(0).end());
            assertThat(Character.isLowSurrogate(snippet.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(snippet.charAt(snippet.length() - 1))).isFalse();
        }
        var split = allocator.select(List.of(candidate(1, "legacy-split", text, 2, 4, 0)), 3);
        assertThat(split.reservedChars()).isEqualTo(3);
        assertThat(allocator.expand(split).get(0).start()).isEqualTo(1);
    }

    @Test void inconsistentChildTextFailsRatherThanInventingEvidence() {
        var source = candidate(1, "bad", "abcdefghij", 2, 6, 50);
        var child = source.childChunk();
        var bad = new ChildChunk(child.id(), child.documentId(), child.parentChunkId(), child.chunkIndex(), "xxxx",
                child.charStart(), child.charEnd(), 1, child.createdAt());
        assertThatThrownBy(() -> allocator.select(List.of(new ExpandedCandidateContext(source.rerankedCandidate(), source.document(), bad, source.parentChunk())), 100))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertEvidence(ContextBudgetAllocator.Plan plan, List<ContextBudgetAllocator.Window> windows) {
        assertThat(windows.stream().mapToInt(w -> w.end() - w.start()).sum()).isLessThanOrEqualTo(plan.budget());
        for (var w : windows) {
            var child = w.candidate().childChunk();
            var parent = w.candidate().parentChunk();
            assertThat(w.start() + parent.charStart()).isLessThanOrEqualTo(child.charStart());
            assertThat(w.end() + parent.charStart()).isGreaterThanOrEqualTo(child.charEnd());
            assertThat(parent.text().substring(w.start(), w.end())).contains(child.text());
        }
    }

    static ExpandedCandidateContext candidate(int rank, String filename, String text, int start, int end, int global) {
        var now = OffsetDateTime.now();
        var document = DocumentMetadata.stored(UUID.randomUUID(), filename, "text/plain", text.length(), "synthetic-hash", "bucket", "synthetic-key", now);
        var parent = new ParentChunk(UUID.randomUUID(), document.id(), 0, text, global, global + text.length(), 1, now);
        var child = new ChildChunk(UUID.randomUUID(), document.id(), parent.id(), rank - 1, text.substring(start, end),
                global + start, global + end, 1, now);
        var fused = new FusedRetrievalCandidate(child.id(), document.id(), parent.id(), child.chunkIndex(), child.text(),
                RetrievalSource.VECTOR, rank, rank / 10d, null, null, 1d / (60 + rank));
        return new ExpandedCandidateContext(new RerankedCandidate(fused, rank, rank, 10d - rank,
                new RerankScoreBreakdown(1, 0, 0, 0), "fixture"), document, child, parent);
    }
}
