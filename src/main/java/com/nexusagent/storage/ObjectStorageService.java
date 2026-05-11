package com.nexusagent.storage;

import java.nio.file.Path;

import reactor.core.publisher.Mono;

public interface ObjectStorageService {

    Mono<StoredObject> store(Path sourcePath, String objectKey, String contentType);

    Mono<byte[]> read(String bucket, String objectKey);

    Mono<Void> delete(String bucket, String objectKey);
}
