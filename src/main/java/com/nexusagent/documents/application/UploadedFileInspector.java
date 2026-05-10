package com.nexusagent.documents.application;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.nexusagent.documents.domain.FileInspection;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class UploadedFileInspector {

    public Mono<FileInspection> inspect(Path path) {
        return Mono.fromCallable(() -> inspectBlocking(path))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private FileInspection inspectBlocking(Path path) throws Exception {
        long sizeBytes = Files.size(path);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];

        try (InputStream inputStream = Files.newInputStream(path)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return new FileInspection(sizeBytes, HexFormat.of().formatHex(digest.digest()));
    }
}
