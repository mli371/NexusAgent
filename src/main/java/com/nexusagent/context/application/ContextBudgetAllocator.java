package com.nexusagent.context.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.nexusagent.context.domain.ContextAllocation;
import com.nexusagent.context.domain.ContextAllocation.Status;
import com.nexusagent.context.domain.ExpandedCandidateContext;

/** Reserve complete evidence before distributing additional parent context. No I/O. */
public final class ContextBudgetAllocator {
    public static final String VERSION = "child-first-v1";

    public Plan select(List<ExpandedCandidateContext> candidates, int budget) {
        if (budget < 1) { throw new IllegalArgumentException("Context budget must be positive"); }
        var decisions = new ArrayList<Decision>();
        var parents = new HashSet<UUID>();
        int reserved = 0;
        for (var candidate : candidates) {
            validate(candidate);
            var child = candidate.childChunk();
            var parent = candidate.parentChunk();
            int start = child.charStart() - parent.charStart();
            int end = child.charEnd() - parent.charStart();
            // A legacy chunk boundary may split a pair. Reserve its other half too.
            if (splitsPair(parent.text(), start)) { start--; }
            if (splitsPair(parent.text(), end)) { end++; }
            Status status;
            if (!parents.add(parent.id())) { status = Status.DUPLICATE_PARENT; }
            else if (end - start > budget - reserved) { status = Status.CHILD_EXCEEDS_REMAINING_BUDGET; }
            else { status = Status.INCLUDED; reserved += end - start; }
            decisions.add(new Decision(candidate, status, start, end));
        }
        return new Plan(List.copyOf(decisions), budget, reserved);
    }

    public List<Window> expand(Plan plan) {
        var selected = plan.decisions().stream().filter(d -> d.status() == Status.INCLUDED).toList();
        int[] quotas = selected.stream().mapToInt(d -> d.end() - d.start()).toArray();
        int remaining = plan.budget() - plan.reservedChars();
        // Water-fill equally among parents still needing context; stable order breaks integer ties.
        while (remaining > 0) {
            int active = 0;
            for (int i = 0; i < quotas.length; i++) {
                if (quotas[i] < selected.get(i).candidate().parentChunk().text().length()) { active++; }
            }
            if (active == 0) { break; }
            int share = Math.max(1, remaining / active);
            for (int i = 0; i < quotas.length && remaining > 0; i++) {
                int room = selected.get(i).candidate().parentChunk().text().length() - quotas[i];
                int grant = Math.min(Math.min(room, share), remaining);
                quotas[i] += grant;
                remaining -= grant;
            }
        }
        var windows = new ArrayList<Window>();
        for (int i = 0; i < selected.size(); i++) {
            Decision d = selected.get(i);
            String text = d.candidate().parentChunk().text();
            int start = Math.max(0, Math.min(d.start() - (quotas[i] - (d.end() - d.start())) / 2, text.length() - quotas[i]));
            int end = start + quotas[i];
            if (splitsPair(text, start)) { start++; }
            if (splitsPair(text, end)) { end--; }
            if (start > d.start() || end < d.end()) { throw new IllegalStateException("Context window lost reserved evidence"); }
            windows.add(new Window(d.candidate(), start, end));
        }
        return List.copyOf(windows);
    }

    public List<ContextAllocation> diagnostics(Plan plan, List<Window> windows) {
        var byChild = new java.util.HashMap<UUID, Window>();
        windows.forEach(w -> byChild.put(w.candidate().childChunk().id(), w));
        return plan.decisions().stream().map(d -> {
            var c = d.candidate();
            var window = d.status() == Status.INCLUDED ? byChild.get(c.childChunk().id()) : null;
            int length = window == null ? 0 : window.end() - window.start();
            return new ContextAllocation(c.document().id(), c.document().originalFilename(), c.parentChunk().id(),
                    c.childChunk().id(), c.rerankedCandidate().rerankedRank(), d.status(), c.childChunk().text().length(),
                    c.parentChunk().text().length(), length, window != null && length < c.parentChunk().text().length());
        }).toList();
    }

    private void validate(ExpandedCandidateContext c) {
        var child = c.childChunk();
        var parent = c.parentChunk();
        var candidate = c.rerankedCandidate().candidate();
        int start = child.charStart() - parent.charStart(), end = child.charEnd() - parent.charStart();
        if (!child.parentChunkId().equals(parent.id()) || !child.documentId().equals(c.document().id())
                || !parent.documentId().equals(c.document().id()) || !candidate.childChunkId().equals(child.id())
                || !candidate.parentChunkId().equals(parent.id()) || !candidate.documentId().equals(c.document().id())
                || candidate.chunkIndex() != child.chunkIndex() || parent.charStart() < 0
                || parent.charEnd() - parent.charStart() != parent.text().length()
                || start < 0 || end <= start || end > parent.text().length()
                || !parent.text().substring(start, end).equals(child.text())) {
            throw new IllegalStateException("Candidate evidence does not match stored parent/child boundaries");
        }
    }

    private static boolean splitsPair(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    public record Decision(ExpandedCandidateContext candidate, Status status, int start, int end) { }
    public record Plan(List<Decision> decisions, int budget, int reservedChars) { }
    public record Window(ExpandedCandidateContext candidate, int start, int end) { }
}
