package com.nexusagent.chunking.application;

import java.util.List;

import com.nexusagent.chunking.domain.ExtractedDocumentText;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.documents.domain.DocumentMetadata;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DocumentTextExtractionService {

    private final List<DocumentTextExtractor> extractors;

    public DocumentTextExtractionService(List<DocumentTextExtractor> extractors) {
        this.extractors = extractors;
    }

    public Mono<ExtractedDocumentText> extract(DocumentMetadata document) {
        return extractors.stream()
                .filter(extractor -> extractor.supports(document))
                .findFirst()
                .map(extractor -> extractor.extract(document))
                .orElseGet(() -> Mono.error(new BadRequestException(
                        "Unsupported document type for text extraction: " + document.contentType()
                )));
    }
}
