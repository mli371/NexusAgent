package com.nexusagent.storage;

public record StoredObject(String bucket, String objectKey, long sizeBytes) {
}
