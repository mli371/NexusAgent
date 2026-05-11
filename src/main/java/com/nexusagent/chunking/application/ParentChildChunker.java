package com.nexusagent.chunking.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.chunking.domain.ChildChunkDraft;
import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import com.nexusagent.chunking.domain.ParentChunkDraft;
import org.springframework.stereotype.Component;

@Component
public class ParentChildChunker {

    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("\\S(?:.|\\R)*?(?=(?:\\R\\s*\\R)|\\z)", Pattern.DOTALL);

    private final ChunkingProperties properties;

    public ParentChildChunker(ChunkingProperties properties) {
        this.properties = properties;
    }

    public ParentChildChunkPlan chunk(String text) {
        validateConfiguration();

        if (text == null || text.isBlank()) {
            return new ParentChildChunkPlan(List.of(), List.of());
        }

        List<TextSpan> parentSpans = splitParentSpans(text);
        List<ParentChunkDraft> parents = new ArrayList<>();
        List<ChildChunkDraft> children = new ArrayList<>();
        int childIndex = 0;

        for (TextSpan span : parentSpans) {
            UUID parentId = UUID.randomUUID();
            String parentText = text.substring(span.start(), span.end());
            ParentChunkDraft parent = new ParentChunkDraft(
                    parentId,
                    parents.size(),
                    parentText,
                    span.start(),
                    span.end(),
                    estimateTokenCount(parentText)
            );
            parents.add(parent);

            List<ChildChunkDraft> parentChildren = splitChildChunks(parent, childIndex);
            children.addAll(parentChildren);
            childIndex += parentChildren.size();
        }

        return new ParentChildChunkPlan(List.copyOf(parents), List.copyOf(children));
    }

    private List<TextSpan> splitParentSpans(String text) {
        List<TextSpan> paragraphSpans = paragraphSpans(text);
        List<TextSpan> parents = new ArrayList<>();
        int parentStart = -1;
        int parentEnd = -1;

        for (TextSpan paragraph : paragraphSpans) {
            if (paragraph.length() > properties.getParentMaxChars()) {
                flushParent(parents, parentStart, parentEnd);
                parentStart = -1;
                parentEnd = -1;
                parents.addAll(splitLongSpan(text, paragraph, properties.getParentMaxChars()));
                continue;
            }

            if (parentStart < 0) {
                parentStart = paragraph.start();
                parentEnd = paragraph.end();
            } else if (paragraph.end() - parentStart <= properties.getParentMaxChars()) {
                parentEnd = paragraph.end();
            } else {
                flushParent(parents, parentStart, parentEnd);
                parentStart = paragraph.start();
                parentEnd = paragraph.end();
            }
        }

        flushParent(parents, parentStart, parentEnd);
        return List.copyOf(parents);
    }

    private List<TextSpan> paragraphSpans(String text) {
        List<TextSpan> spans = new ArrayList<>();
        Matcher matcher = PARAGRAPH_PATTERN.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = trimRight(text, matcher.end());
            if (end > start) {
                spans.add(new TextSpan(start, end));
            }
        }
        return spans;
    }

    private List<TextSpan> splitLongSpan(String text, TextSpan span, int maxChars) {
        List<TextSpan> spans = new ArrayList<>();
        int start = span.start();

        while (start < span.end()) {
            start = skipWhitespace(text, start, span.end());
            if (start >= span.end()) {
                break;
            }

            int hardEnd = Math.min(start + maxChars, span.end());
            int end = hardEnd;
            if (hardEnd < span.end()) {
                int preferred = previousWhitespace(text, start + Math.max(1, maxChars / 2), hardEnd);
                if (preferred > start) {
                    end = trimRight(text, preferred);
                }
            }
            if (end <= start) {
                end = hardEnd;
            }

            spans.add(new TextSpan(start, end));
            start = end;
        }

        return spans;
    }

    private List<ChildChunkDraft> splitChildChunks(ParentChunkDraft parent, int firstChildIndex) {
        String parentText = parent.text();
        List<ChildChunkDraft> children = new ArrayList<>();
        int maxChars = properties.getChildMaxChars();
        int overlap = properties.getChildOverlapChars();
        int localStart = 0;

        while (localStart < parentText.length()) {
            localStart = skipWhitespace(parentText, localStart, parentText.length());
            if (localStart >= parentText.length()) {
                break;
            }

            int hardEnd = Math.min(localStart + maxChars, parentText.length());
            int localEnd = hardEnd;
            if (hardEnd < parentText.length()) {
                int preferred = previousWhitespace(parentText, localStart + Math.max(1, maxChars / 2), hardEnd);
                if (preferred > localStart) {
                    localEnd = trimRight(parentText, preferred);
                }
            }
            if (localEnd <= localStart) {
                localEnd = hardEnd;
            }

            String childText = parentText.substring(localStart, localEnd);
            children.add(new ChildChunkDraft(
                    UUID.randomUUID(),
                    parent.id(),
                    firstChildIndex + children.size(),
                    childText,
                    parent.charStart() + localStart,
                    parent.charStart() + localEnd,
                    estimateTokenCount(childText)
            ));

            if (localEnd >= parentText.length()) {
                break;
            }

            int nextStart = Math.max(localEnd - overlap, localStart + 1);
            if (nextStart <= localStart) {
                nextStart = localEnd;
            }
            localStart = nextStart;
        }

        return children;
    }

    private void flushParent(List<TextSpan> parents, int parentStart, int parentEnd) {
        if (parentStart >= 0 && parentEnd > parentStart) {
            parents.add(new TextSpan(parentStart, parentEnd));
        }
    }

    private int previousWhitespace(String text, int minExclusive, int maxExclusive) {
        int index = Math.min(maxExclusive, text.length()) - 1;
        while (index > minExclusive) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index;
            }
            index--;
        }
        return -1;
    }

    private int skipWhitespace(String text, int start, int limit) {
        int index = start;
        while (index < limit && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private int trimRight(String text, int endExclusive) {
        int end = endExclusive;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private int estimateTokenCount(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }

    private void validateConfiguration() {
        if (properties.getParentMaxChars() < 1) {
            throw new BadRequestException("parentMaxChars must be greater than 0");
        }
        if (properties.getChildMaxChars() < 1) {
            throw new BadRequestException("childMaxChars must be greater than 0");
        }
        if (properties.getChildOverlapChars() < 0 || properties.getChildOverlapChars() >= properties.getChildMaxChars()) {
            throw new BadRequestException("childOverlapChars must be greater than or equal to 0 and less than childMaxChars");
        }
    }

    private record TextSpan(int start, int end) {

        int length() {
            return end - start;
        }
    }
}
