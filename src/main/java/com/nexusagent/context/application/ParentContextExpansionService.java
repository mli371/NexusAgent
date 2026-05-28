package com.nexusagent.context.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.context.domain.ExpandedCandidateContext;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ParentContextExpansionService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;

    public ParentContextExpansionService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    public Mono<List<ExpandedCandidateContext>> expand(List<RerankedCandidate> rerankedCandidates) {
        return expand(rerankedCandidates, RequestContext.defaults());
    }

    public Mono<List<ExpandedCandidateContext>> expand(
            List<RerankedCandidate> rerankedCandidates,
            RequestContext context
    ) {
        if (rerankedCandidates.isEmpty()) {
            return Mono.just(List.of());
        }
        RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;

        List<UUID> documentIds = rerankedCandidates.stream()
                .map(candidate -> candidate.candidate().documentId())
                .distinct()
                .toList();

        return Flux.fromIterable(documentIds)
                .concatMap(documentId -> loadDocumentChunks(documentId, effectiveContext))
                .collectList()
                .map(loadedDocuments -> expandCandidates(rerankedCandidates, loadedDocuments));
    }

    private Mono<LoadedDocumentChunks> loadDocumentChunks(UUID documentId, RequestContext context) {
        return Mono.zip(
                        documentRepository.findById(documentId, context),
                        chunkRepository.findByDocumentId(documentId)
                )
                .map(tuple -> new LoadedDocumentChunks(
                        tuple.getT1(),
                        tuple.getT2().childChunks().stream()
                                .collect(Collectors.toMap(ChildChunk::id, Function.identity())),
                        tuple.getT2().parentChunks().stream()
                                .collect(Collectors.toMap(ParentChunk::id, Function.identity()))
                ));
    }

    private List<ExpandedCandidateContext> expandCandidates(
            List<RerankedCandidate> rerankedCandidates,
            List<LoadedDocumentChunks> loadedDocuments
    ) {
        Map<UUID, LoadedDocumentChunks> documentsById = new HashMap<>();
        for (LoadedDocumentChunks loadedDocument : loadedDocuments) {
            documentsById.put(loadedDocument.document().id(), loadedDocument);
        }

        return rerankedCandidates.stream()
                .map(candidate -> expandCandidate(candidate, documentsById))
                .flatMap(List::stream)
                .toList();
    }

    private List<ExpandedCandidateContext> expandCandidate(
            RerankedCandidate candidate,
            Map<UUID, LoadedDocumentChunks> documentsById
    ) {
        LoadedDocumentChunks loadedDocument = documentsById.get(candidate.candidate().documentId());
        if (loadedDocument == null) {
            return List.of();
        }

        ChildChunk childChunk = loadedDocument.childChunksById().get(candidate.candidate().childChunkId());
        ParentChunk parentChunk = loadedDocument.parentChunksById().get(candidate.candidate().parentChunkId());
        if (childChunk == null || parentChunk == null) {
            return List.of();
        }

        return List.of(new ExpandedCandidateContext(
                candidate,
                loadedDocument.document(),
                childChunk,
                parentChunk
        ));
    }

    private record LoadedDocumentChunks(
            DocumentMetadata document,
            Map<UUID, ChildChunk> childChunksById,
            Map<UUID, ParentChunk> parentChunksById
    ) {
    }
}
