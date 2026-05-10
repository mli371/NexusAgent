package com.nexusagent.storage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class MinioObjectStorageService implements ObjectStorageService {

    private final MinioClient minioClient;
    private final MinioStorageProperties properties;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public MinioObjectStorageService(MinioClient minioClient, MinioStorageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public Mono<StoredObject> store(Path sourcePath, String objectKey, String contentType) {
        return Mono.fromCallable(() -> storeBlocking(sourcePath, objectKey, contentType))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.getOperationTimeout());
    }

    @Override
    public Mono<Void> delete(String bucket, String objectKey) {
        return Mono.fromCallable(() -> {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build());
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.getOperationTimeout())
                .then();
    }

    private StoredObject storeBlocking(Path sourcePath, String objectKey, String contentType) throws Exception {
        ensureBucketExists();
        long sizeBytes = Files.size(sourcePath);
        String resolvedContentType = contentType == null || contentType.isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : contentType;

        try (InputStream inputStream = Files.newInputStream(sourcePath)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(inputStream, sizeBytes, -1)
                    .contentType(resolvedContentType)
                    .build());
        }

        return new StoredObject(properties.getBucket(), objectKey, sizeBytes);
    }

    private void ensureBucketExists() throws Exception {
        if (bucketReady.get()) {
            return;
        }

        synchronized (bucketReady) {
            if (bucketReady.get()) {
                return;
            }

            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(properties.getBucket())
                        .build());
            }
            bucketReady.set(true);
        }
    }
}
