package com.nexusagent.chunking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentStatus;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PlainTextDocumentTextExtractorTest {

    private final ObjectStorageService objectStorageService = Mockito.mock(ObjectStorageService.class);
    private final PlainTextDocumentTextExtractor extractor = new PlainTextDocumentTextExtractor(objectStorageService);

    @Test
    void supportsPlainTextAndMarkdownByContentTypeOrFilename() {
        assertThat(extractor.supports(document("notes.txt", "text/plain"))).isTrue();
        assertThat(extractor.supports(document("notes.md", "application/octet-stream"))).isTrue();
        assertThat(extractor.supports(document("notes.markdown", "application/octet-stream"))).isTrue();
        assertThat(extractor.supports(document("notes.bin", "application/pdf"))).isFalse();
    }

    @Test
    void extractsUtf8TextAndNormalizesLineEndings() {
        DocumentMetadata document = document("notes.md", "text/markdown; charset=utf-8");
        byte[] bytes = "\uFEFF# Title\r\n\r\nBody line\rSecond line".getBytes(StandardCharsets.UTF_8);
        when(objectStorageService.read(document.minioBucket(), document.minioObjectKey()))
                .thenReturn(Mono.just(bytes));

        StepVerifier.create(extractor.extract(document))
                .assertNext(extracted -> {
                    assertThat(extracted.documentId()).isEqualTo(document.id());
                    assertThat(extracted.text()).isEqualTo("# Title\n\nBody line\nSecond line");
                    assertThat(extracted.sourceContentType()).isEqualTo(document.contentType());
                })
                .verifyComplete();
    }

    private DocumentMetadata document(String filename, String contentType) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new DocumentMetadata(
                id,
                "default",
                "anonymous",
                DocumentVisibility.TENANT,
                filename,
                contentType,
                100,
                "sha",
                "bucket",
                "documents/%s/%s".formatted(id, filename),
                DocumentStatus.STORED,
                now,
                now
        );
    }
}
