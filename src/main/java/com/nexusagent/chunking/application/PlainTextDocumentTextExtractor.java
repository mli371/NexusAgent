package com.nexusagent.chunking.application;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.nexusagent.chunking.domain.ExtractedDocumentText;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.storage.ObjectStorageService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PlainTextDocumentTextExtractor implements DocumentTextExtractor {

    private final ObjectStorageService objectStorageService;

    public PlainTextDocumentTextExtractor(ObjectStorageService objectStorageService) {
        this.objectStorageService = objectStorageService;
    }

    @Override
    public boolean supports(DocumentMetadata document) {
        String filename = document.originalFilename().toLowerCase(Locale.ROOT);
        String contentType = document.contentType() == null ? "" : document.contentType().toLowerCase(Locale.ROOT);

        return contentType.startsWith("text/plain")
                || contentType.startsWith("text/markdown")
                || contentType.startsWith("text/x-markdown")
                || contentType.startsWith("application/markdown")
                || filename.endsWith(".txt")
                || filename.endsWith(".md")
                || filename.endsWith(".markdown");
    }

    @Override
    public Mono<ExtractedDocumentText> extract(DocumentMetadata document) {
        return objectStorageService.read(document.minioBucket(), document.minioObjectKey())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .map(this::stripUtf8Bom)
                .map(this::normalizeLineEndings)
                .map(text -> new ExtractedDocumentText(document.id(), text, document.contentType()));
    }

    private String stripUtf8Bom(String text) {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    private String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
