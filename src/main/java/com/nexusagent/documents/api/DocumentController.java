package com.nexusagent.documents.api;

import java.util.UUID;

import com.nexusagent.documents.application.DocumentQueryService;
import com.nexusagent.documents.application.DocumentUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentUploadService documentUploadService;
    private final DocumentQueryService documentQueryService;

    public DocumentController(DocumentUploadService documentUploadService, DocumentQueryService documentQueryService) {
        this.documentUploadService = documentUploadService;
        this.documentQueryService = documentQueryService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<DocumentResponse>> upload(@RequestPart("file") Mono<FilePart> filePart) {
        return filePart
                .flatMap(documentUploadService::upload)
                .map(DocumentResponse::from)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping("/{id}")
    public Mono<DocumentResponse> getById(@PathVariable UUID id) {
        return documentQueryService.getById(id)
                .map(DocumentResponse::from);
    }

    @GetMapping
    public Flux<DocumentResponse> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return documentQueryService.list(limit, offset)
                .map(DocumentResponse::from);
    }
}
