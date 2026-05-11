package com.nexusagent.chunking.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.domain.ParentChunk;

public record ChunkedDocumentResponse(
        UUID documentId,
        int parentChunkCount,
        int childChunkCount,
        List<ParentChunkResponse> parentChunks,
        List<ChildChunkResponse> childChunks
) {

    public static ChunkedDocumentResponse from(ChunkedDocument chunkedDocument) {
        return new ChunkedDocumentResponse(
                chunkedDocument.documentId(),
                chunkedDocument.parentChunks().size(),
                chunkedDocument.childChunks().size(),
                chunkedDocument.parentChunks().stream().map(ParentChunkResponse::from).toList(),
                chunkedDocument.childChunks().stream().map(ChildChunkResponse::from).toList()
        );
    }

    public record ParentChunkResponse(
            UUID id,
            int chunkIndex,
            String text,
            int charStart,
            int charEnd,
            int tokenCount,
            OffsetDateTime createdAt
    ) {

        static ParentChunkResponse from(ParentChunk parentChunk) {
            return new ParentChunkResponse(
                    parentChunk.id(),
                    parentChunk.chunkIndex(),
                    parentChunk.text(),
                    parentChunk.charStart(),
                    parentChunk.charEnd(),
                    parentChunk.tokenCount(),
                    parentChunk.createdAt()
            );
        }
    }

    public record ChildChunkResponse(
            UUID id,
            UUID parentChunkId,
            int chunkIndex,
            String text,
            int charStart,
            int charEnd,
            int tokenCount,
            OffsetDateTime createdAt
    ) {

        static ChildChunkResponse from(ChildChunk childChunk) {
            return new ChildChunkResponse(
                    childChunk.id(),
                    childChunk.parentChunkId(),
                    childChunk.chunkIndex(),
                    childChunk.text(),
                    childChunk.charStart(),
                    childChunk.charEnd(),
                    childChunk.tokenCount(),
                    childChunk.createdAt()
            );
        }
    }
}
