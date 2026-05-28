package com.nexusagent.documents.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.domain.FileInspection;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.enterprise.audit.AuditEventType;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.storage.ObjectKeyFactory;
import com.nexusagent.storage.ObjectStorageService;
import com.nexusagent.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class DocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);
    private static final String DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    private final ObjectStorageService objectStorageService;
    private final DocumentRepository documentRepository;
    private final ObjectKeyFactory objectKeyFactory;
    private final UploadedFileInspector uploadedFileInspector;
    private final UploadProperties uploadProperties;
    private final AuditService auditService;
    private final Clock clock;

    public DocumentUploadService(
            ObjectStorageService objectStorageService,
            DocumentRepository documentRepository,
            ObjectKeyFactory objectKeyFactory,
            UploadedFileInspector uploadedFileInspector,
            UploadProperties uploadProperties,
            AuditService auditService,
            Clock clock
    ) {
        this.objectStorageService = objectStorageService;
        this.documentRepository = documentRepository;
        this.objectKeyFactory = objectKeyFactory;
        this.uploadedFileInspector = uploadedFileInspector;
        this.uploadProperties = uploadProperties;
        this.auditService = auditService;
        this.clock = clock;
    }

    public Mono<DocumentMetadata> upload(FilePart filePart) {
        return upload(filePart, RequestContext.defaults(), DocumentVisibility.TENANT);
    }

    public Mono<DocumentMetadata> upload(FilePart filePart, RequestContext context, DocumentVisibility visibility) {
        return Mono.defer(() -> {
            if (filePart == null) {
                return Mono.error(new BadRequestException("file part is required"));
            }
            RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
            DocumentVisibility effectiveVisibility = visibility == null ? DocumentVisibility.TENANT : visibility;

            UUID documentId = UUID.randomUUID();
            String originalFilename = objectKeyFactory.sanitizeFilename(filePart.filename());
            String contentType = contentType(filePart);
            String objectKey = objectKeyFactory.documentObjectKey(documentId, originalFilename);

            return Mono.usingWhen(
                    createTempFile(documentId),
                    tempFile -> transferAndStore(
                            filePart,
                            tempFile,
                            documentId,
                            originalFilename,
                            contentType,
                            objectKey,
                            effectiveContext,
                            effectiveVisibility
                    ),
                    this::deleteTempFile,
                    (tempFile, error) -> deleteTempFile(tempFile),
                    this::deleteTempFile
            );
        });
    }

    private Mono<DocumentMetadata> transferAndStore(
            FilePart filePart,
            Path tempFile,
            UUID documentId,
            String originalFilename,
            String contentType,
            String objectKey,
            RequestContext context,
            DocumentVisibility visibility
    ) {
        return filePart.transferTo(tempFile)
                .then(uploadedFileInspector.inspect(tempFile))
                .flatMap(this::validateFile)
                .flatMap(inspection -> objectStorageService.store(tempFile, objectKey, contentType)
                        .flatMap(storedObject -> saveMetadataAfterObjectStored(
                                documentId,
                                originalFilename,
                                contentType,
                                inspection,
                                storedObject,
                                context,
                                visibility
                        )));
    }

    private Mono<DocumentMetadata> saveMetadataAfterObjectStored(
            UUID documentId,
            String originalFilename,
            String contentType,
            FileInspection inspection,
            StoredObject storedObject,
            RequestContext context,
            DocumentVisibility visibility
    ) {
        return saveMetadata(documentId, originalFilename, contentType, inspection, storedObject, context, visibility)
                .flatMap(metadata -> auditService.record(
                                context,
                                "upload-" + documentId,
                                AuditEventType.DOCUMENT_UPLOADED,
                                "document",
                                documentId,
                                documentId,
                                java.util.Map.of(
                                        "originalFilename", metadata.originalFilename(),
                                        "sizeBytes", metadata.sizeBytes(),
                                        "contentType", metadata.contentType(),
                                        "visibility", metadata.visibility().name()
                                )
                        )
                        .thenReturn(metadata))
                .doOnSuccess(metadata -> log.info(
                        "document_uploaded documentId={} tenantId={} actorId={} sizeBytes={} visibility={}",
                        metadata.id(),
                        metadata.tenantId(),
                        metadata.ownerId(),
                        metadata.sizeBytes(),
                        metadata.visibility()
                ))
                .onErrorResume(error -> cleanupStoredObject(storedObject, error));
    }

    private Mono<DocumentMetadata> cleanupStoredObject(StoredObject storedObject, Throwable originalError) {
        return objectStorageService.delete(storedObject.bucket(), storedObject.objectKey())
                .doOnSuccess(ignored -> log.info(
                        "Deleted MinIO object {} from bucket {} after metadata persistence failed",
                        storedObject.objectKey(),
                        storedObject.bucket()
                ))
                .onErrorResume(cleanupError -> {
                    log.warn(
                            "Could not delete MinIO object {} from bucket {} after metadata persistence failed: {}",
                            storedObject.objectKey(),
                            storedObject.bucket(),
                            cleanupError.getMessage()
                    );
                    log.debug("MinIO cleanup failure details", cleanupError);
                    return Mono.empty();
                })
                .then(Mono.error(originalError));
    }

    private Mono<DocumentMetadata> saveMetadata(
            UUID documentId,
            String originalFilename,
            String contentType,
            FileInspection inspection,
            StoredObject storedObject,
            RequestContext context,
            DocumentVisibility visibility
    ) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        DocumentMetadata metadata = DocumentMetadata.stored(
                documentId,
                context.tenantId(),
                context.actorId(),
                visibility,
                originalFilename,
                contentType,
                inspection.sizeBytes(),
                inspection.sha256(),
                storedObject.bucket(),
                storedObject.objectKey(),
                now
        );
        return documentRepository.save(metadata);
    }

    private Mono<FileInspection> validateFile(FileInspection inspection) {
        if (inspection.sizeBytes() <= 0) {
            return Mono.error(new BadRequestException("Uploaded file must not be empty"));
        }
        if (inspection.sizeBytes() > uploadProperties.getMaxFileSizeBytes()) {
            return Mono.error(new BadRequestException(
                    "Uploaded file exceeds max size of " + uploadProperties.getMaxFileSizeBytes() + " bytes"
            ));
        }
        return Mono.just(inspection);
    }

    private String contentType(FilePart filePart) {
        MediaType mediaType = filePart.headers().getContentType();
        return mediaType == null ? DEFAULT_CONTENT_TYPE : mediaType.toString();
    }

    private Mono<Path> createTempFile(UUID documentId) {
        return Mono.fromCallable(() -> Files.createTempFile("nexus-" + documentId + "-", ".upload"))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> deleteTempFile(Path path) {
        return Mono.fromRunnable(() -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        log.warn("Could not delete temporary upload file {}", path, exception);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
