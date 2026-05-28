package com.nexusagent.documents.application;

import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class DocumentQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentRepository documentRepository;

    public DocumentQueryService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public Mono<DocumentMetadata> getById(UUID id) {
        return documentRepository.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + id)));
    }

    public Mono<DocumentMetadata> getById(UUID id, RequestContext context) {
        return documentRepository.findById(id, context)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + id)));
    }

    public Flux<DocumentMetadata> list(int limit, int offset) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            return Flux.error(new BadRequestException("limit must be between 1 and " + MAX_PAGE_SIZE));
        }
        if (offset < 0) {
            return Flux.error(new BadRequestException("offset must be greater than or equal to 0"));
        }
        return documentRepository.findAll(limit, offset);
    }

    public Flux<DocumentMetadata> list(RequestContext context, int limit, int offset) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            return Flux.error(new BadRequestException("limit must be between 1 and " + MAX_PAGE_SIZE));
        }
        if (offset < 0) {
            return Flux.error(new BadRequestException("offset must be greater than or equal to 0"));
        }
        return documentRepository.findAll(context, limit, offset);
    }
}
