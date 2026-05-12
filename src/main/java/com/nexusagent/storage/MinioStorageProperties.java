package com.nexusagent.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.storage.minio")
public class MinioStorageProperties {

    private String endpoint = "http://localhost:9000";
    private String accessKey = "minioadmin";
    private String secretKey = "minio-local-password";
    private String bucket = "nexus-documents";
    private Duration operationTimeout = Duration.ofSeconds(10);

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public Duration getOperationTimeout() {
        return operationTimeout;
    }

    public void setOperationTimeout(Duration operationTimeout) {
        this.operationTimeout = operationTimeout;
    }
}
