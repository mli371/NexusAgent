package com.nexusagent.query.live;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.query.api.QueryScopeSummary;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Rechecks are deliberately outside model calls: no DB transaction is held across remote I/O. */
public class LiveQueryGuard {
    private final DocumentRepository documents;
    private final ChildChunkEmbeddingRepository embeddings;
    private final EmbeddingService model;
    private final ChunkRepository chunks;
    private final QueryLibraryRepository library;

    public LiveQueryGuard(DocumentRepository documents, ChildChunkEmbeddingRepository embeddings,
                          EmbeddingService model, ChunkRepository chunks, QueryLibraryRepository library) {
        this.documents = documents;
        this.embeddings = embeddings;
        this.model = model;
        this.chunks = chunks;
        this.library = library;
    }

    Mono<List<QueryLibraryRepository.DocumentCoverage>> librarySnapshot(LiveQueryInput input) {
        return library.visibleCoverage(input.context(), model.modelInfo()).collectList();
    }

    ScopedQuery selectReady(LiveQueryInput input, List<QueryLibraryRepository.DocumentCoverage> rows) {
        if (rows.size() > QueryLibraryRepository.MAX_DOCUMENTS) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "LIBRARY_SCOPE_TOO_LARGE",
                    "Learning-mode library scope supports up to 200 accessible documents; choose documents explicitly");
        }
        Map<String, Integer> excluded = new LinkedHashMap<>();
        for (var row : rows) {
            var coverage = row.coverage();
            if (!coverage.complete()) {
                String reason = coverage.childCount() == 0 ? "CHUNKING_REQUIRED"
                        : coverage.mismatchedCount() > 0 ? "EMBEDDING_MODEL_MISMATCH" : "EMBEDDING_INCOMPLETE";
                excluded.merge(reason, 1, Integer::sum);
            }
        }
        var ready = rows.stream().filter(row -> row.coverage().complete()).map(QueryLibraryRepository.DocumentCoverage::documentId).toList();
        if (ready.isEmpty()) {
            throw new OperationException(HttpStatus.CONFLICT, "LIBRARY_NOT_READY",
                    "No ready documents in accessible library (accessible=" + rows.size() + ", excluded=" + rows.size()
                            + "). Prepare accessible documents with the current embedding model first");
        }
        return new ScopedQuery(input.withDocuments(ready), new QueryScopeSummary("library", rows.size(), ready.size(),
                rows.size() - ready.size(), Map.copyOf(excluded)));
    }

    record ScopedQuery(LiveQueryInput input, QueryScopeSummary summary) { }

    Mono<Void> access(LiveQueryInput input) {
        return Flux.fromIterable(input.documentIds()).concatMap(id -> documents.findById(id, input.context())
                .switchIfEmpty(Mono.error(new OperationException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_ACCESSIBLE",
                        "A selected document does not exist or is not accessible")))).then();
    }

    Mono<Void> readiness(LiveQueryInput input) {
        return Flux.fromIterable(input.documentIds()).concatMap(id -> embeddings.coverage(id, model.modelInfo())
                .flatMap(coverage -> {
                    if (coverage.childCount() == 0) {
                        return Mono.error(new OperationException(HttpStatus.CONFLICT, "CHUNKING_REQUIRED",
                                "Chunk all selected documents before asking a live question"));
                    }
                    if (coverage.mismatchedCount() > 0) { return Mono.error(OperationException.modelMismatch()); }
                    if (!coverage.complete()) {
                        return Mono.error(new OperationException(HttpStatus.CONFLICT, "EMBEDDING_INCOMPLETE",
                                "Selected documents need embeddings for every child chunk using the current model"));
                    }
                    return Mono.empty();
                })).then();
    }

    Mono<Void> evidence(LiveQueryInput input, ContextBuildResult result) {
        return access(input).then(readiness(input)).then(Mono.defer(() -> {
            if (result.rerankedCandidates().stream().anyMatch(c -> !input.documentIds().contains(c.candidate().documentId()))
                    || result.selectedChildChunks().stream().anyMatch(c -> !input.documentIds().contains(c.documentId()))
                    || result.expandedParentContexts().stream().anyMatch(c -> !input.documentIds().contains(c.documentId()))
                    || result.citations().stream().anyMatch(c -> !input.documentIds().contains(c.documentId()))
                    || result.retrievalResult() != null && (
                        result.retrievalResult().vectorCandidates().stream().anyMatch(c -> !input.documentIds().contains(c.documentId()))
                        || result.retrievalResult().fullTextCandidates().stream().anyMatch(c -> !input.documentIds().contains(c.documentId()))
                        || result.retrievalResult().fusedCandidates().stream().anyMatch(c -> !input.documentIds().contains(c.documentId())))) {
                return Mono.error(changed());
            }
            return Flux.fromIterable(result.expandedParentContexts()).concatMap(parent ->
                    documents.findById(parent.documentId(), input.context())
                            .switchIfEmpty(Mono.error(changed()))
                            .flatMap(document -> chunks.findByDocumentId(parent.documentId()).map(current -> {
                                Map<java.util.UUID, ParentChunk> parents = current.parentChunks().stream()
                                        .collect(Collectors.toMap(ParentChunk::id, Function.identity()));
                                Map<java.util.UUID, ChildChunk> children = current.childChunks().stream()
                                        .collect(Collectors.toMap(ChildChunk::id, Function.identity()));
                                ParentChunk stored = parents.get(parent.parentChunkId());
                                if (stored == null || !document.originalFilename().equals(parent.originalFilename())
                                        || parent.charStart() < stored.charStart() || parent.charEnd() > stored.charEnd()
                                        || parent.charStart() > parent.charEnd()
                                        || !stored.text().substring(parent.charStart() - stored.charStart(),
                                        parent.charEnd() - stored.charStart()).equals(parent.text())) {
                                    throw changed();
                                }
                                for (var selected : result.selectedChildChunks()) {
                                    if (!selected.parentChunkId().equals(parent.parentChunkId())) { continue; }
                                    ChildChunk child = children.get(selected.childChunkId());
                                    if (child == null || !child.parentChunkId().equals(parent.parentChunkId())
                                            || !child.documentId().equals(selected.documentId())
                                            || child.chunkIndex() != selected.chunkIndex()
                                            || child.charStart() != selected.charStart() || child.charEnd() != selected.charEnd()) {
                                        throw changed();
                                    }
                                }
                                return true;
                            }))).then();
        }));
    }

    static OperationException changed() {
        return new OperationException(HttpStatus.CONFLICT, "DOCUMENT_CHANGED",
                "Selected evidence or access changed during this query; run a fresh query");
    }
}
