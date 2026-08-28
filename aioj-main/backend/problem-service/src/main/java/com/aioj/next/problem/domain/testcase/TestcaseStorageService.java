package com.aioj.next.problem.domain.testcase;

import java.io.InputStream;
import java.nio.file.Path;

public interface TestcaseStorageService {
    String LOCAL_PROVIDER = "LOCAL";

    StoredChunk writeStagingChunk(String uploadId, int index, InputStream input);

    String commitTempChunk(Path stagingPath, String uploadId, int index);

    MergeResult mergePackage(String uploadId, Long problemId, String sha256, int totalChunks);

    Path resolveStorageKey(String storageKey);

    String packageStorageKey(Long problemId, String sha256);

    boolean exists(String storagePath);

    void deleteIfExists(Path path);

    void deleteTempUpload(String uploadId);

    record StoredChunk(Path stagingPath, long sizeBytes, String sha256) {
    }

    record MergeResult(Path packagePath, String storageKey, long sizeBytes, String sha256) {
    }
}
