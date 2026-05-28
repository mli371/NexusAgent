package com.nexusagent.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentStatus;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.storage.ObjectKeyFactory;
import com.nexusagent.storage.ObjectStorageService;
import com.nexusagent.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DocumentUploadServiceTest {

    private ObjectStorageService objectStorageService;
    private DocumentRepository documentRepository;
    private AuditService auditService;
    private UploadProperties uploadProperties;
    private DocumentUploadService service;

    @BeforeEach
    void setUp() {
        objectStorageService = mock(ObjectStorageService.class);
        documentRepository = mock(DocumentRepository.class);
        auditService = mock(AuditService.class);
        uploadProperties = new UploadProperties();
        uploadProperties.setMaxFileSizeBytes(1024);
        lenient().when(auditService.record(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        service = new DocumentUploadService(
                objectStorageService,
                documentRepository,
                new ObjectKeyFactory(),
                new UploadedFileInspector(),
                uploadProperties,
                auditService,
                Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void uploadStoresObjectAndPersistsMetadata() {
        byte[] content = "hello nexus".getBytes(StandardCharsets.UTF_8);
        FilePart filePart = filePart("sample.txt", MediaType.TEXT_PLAIN, content);
        AtomicReference<Path> storedPath = new AtomicReference<>();

        when(objectStorageService.store(any(Path.class), any(String.class), eq(MediaType.TEXT_PLAIN_VALUE)))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(0);
                    String objectKey = invocation.getArgument(1);
                    storedPath.set(path);
                    return Mono.just(new StoredObject("test-bucket", objectKey, Files.size(path)));
                });
        when(documentRepository.save(any(DocumentMetadata.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        RequestContext context = new RequestContext("tenant-a", "actor-1");

        StepVerifier.create(service.upload(filePart, context, DocumentVisibility.PRIVATE))
                .assertNext(metadata -> {
                    assertThat(metadata.tenantId()).isEqualTo("tenant-a");
                    assertThat(metadata.ownerId()).isEqualTo("actor-1");
                    assertThat(metadata.visibility()).isEqualTo(DocumentVisibility.PRIVATE);
                    assertThat(metadata.originalFilename()).isEqualTo("sample.txt");
                    assertThat(metadata.contentType()).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
                    assertThat(metadata.sizeBytes()).isEqualTo(content.length);
                    assertThat(metadata.sha256()).hasSize(64);
                    assertThat(metadata.minioBucket()).isEqualTo("test-bucket");
                    assertThat(metadata.minioObjectKey()).startsWith("documents/");
                    assertThat(metadata.status()).isEqualTo(DocumentStatus.STORED);
                    assertThat(metadata.createdAt().toInstant()).isEqualTo(Instant.parse("2026-05-07T12:00:00Z"));
                    assertThat(metadata.updatedAt()).isEqualTo(metadata.createdAt());
                })
                .verifyComplete();

        ArgumentCaptor<DocumentMetadata> metadataCaptor = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentRepository).save(metadataCaptor.capture());
        verify(objectStorageService, never()).delete(any(), any());
        verify(auditService).record(any(), any(), any(), any(), any(), any(), any());
        assertThat(metadataCaptor.getValue().originalFilename()).isEqualTo("sample.txt");
        assertThat(storedPath.get()).isNotNull();
        assertThat(storedPath.get()).doesNotExist();
    }

    @Test
    void uploadDeletesStoredObjectWhenMetadataPersistenceFails() {
        byte[] content = "metadata failure cleanup".getBytes(StandardCharsets.UTF_8);
        FilePart filePart = filePart("sample.txt", MediaType.TEXT_PLAIN, content);
        RuntimeException persistenceFailure = new RuntimeException("database insert failed");
        AtomicReference<Path> storedPath = new AtomicReference<>();
        AtomicReference<String> storedObjectKey = new AtomicReference<>();

        when(objectStorageService.store(any(Path.class), any(String.class), eq(MediaType.TEXT_PLAIN_VALUE)))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(0);
                    String objectKey = invocation.getArgument(1);
                    storedPath.set(path);
                    storedObjectKey.set(objectKey);
                    return Mono.just(new StoredObject("test-bucket", objectKey, Files.size(path)));
                });
        when(documentRepository.save(any(DocumentMetadata.class))).thenReturn(Mono.error(persistenceFailure));
        when(objectStorageService.delete(eq("test-bucket"), any(String.class))).thenReturn(Mono.empty());

        StepVerifier.create(service.upload(filePart))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(persistenceFailure))
                .verify();

        verify(objectStorageService).delete("test-bucket", storedObjectKey.get());
        assertThat(storedPath.get()).isNotNull();
        assertThat(storedPath.get()).doesNotExist();
    }

    @Test
    void uploadKeepsOriginalMetadataErrorWhenBestEffortCleanupFails() {
        byte[] content = "metadata failure cleanup failure".getBytes(StandardCharsets.UTF_8);
        FilePart filePart = filePart("sample.txt", MediaType.TEXT_PLAIN, content);
        RuntimeException persistenceFailure = new RuntimeException("database insert failed");
        RuntimeException cleanupFailure = new RuntimeException("minio delete failed");

        when(objectStorageService.store(any(Path.class), any(String.class), eq(MediaType.TEXT_PLAIN_VALUE)))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(0);
                    String objectKey = invocation.getArgument(1);
                    return Mono.just(new StoredObject("test-bucket", objectKey, Files.size(path)));
                });
        when(documentRepository.save(any(DocumentMetadata.class))).thenReturn(Mono.error(persistenceFailure));
        when(objectStorageService.delete(eq("test-bucket"), any(String.class))).thenReturn(Mono.error(cleanupFailure));

        StepVerifier.create(service.upload(filePart))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(persistenceFailure))
                .verify();

        verify(objectStorageService).delete(eq("test-bucket"), any(String.class));
    }

    @Test
    void uploadRejectsEmptyFileBeforeStorage() {
        AtomicReference<Path> transferredPath = new AtomicReference<>();
        FilePart filePart = filePart("empty.txt", MediaType.TEXT_PLAIN, new byte[0], transferredPath);

        StepVerifier.create(service.upload(filePart))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(error).hasMessage("Uploaded file must not be empty");
                })
                .verify();

        verifyNoInteractions(objectStorageService);
        verify(documentRepository, never()).save(any());
        assertThat(transferredPath.get()).isNotNull();
        assertThat(transferredPath.get()).doesNotExist();
    }

    private FilePart filePart(String filename, MediaType contentType, byte[] content) {
        return filePart(filename, contentType, content, new AtomicReference<>());
    }

    private FilePart filePart(
            String filename,
            MediaType contentType,
            byte[] content,
            AtomicReference<Path> transferredPath
    ) {
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);

        when(filePart.filename()).thenReturn(filename);
        when(filePart.headers()).thenReturn(headers);
        when(filePart.transferTo(any(Path.class))).thenAnswer(invocation -> {
            Path target = invocation.getArgument(0);
            transferredPath.set(target);
            return Mono.fromRunnable(() -> write(target, content));
        });

        return filePart;
    }

    private void write(Path target, byte[] content) {
        try {
            Files.write(target, content);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
