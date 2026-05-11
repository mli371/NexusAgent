package com.nexusagent.chunking.application;

import com.nexusagent.chunking.domain.ExtractedDocumentText;
import com.nexusagent.documents.domain.DocumentMetadata;
import reactor.core.publisher.Mono;

public interface DocumentTextExtractor {

    boolean supports(DocumentMetadata document);

    Mono<ExtractedDocumentText> extract(DocumentMetadata document);
}
