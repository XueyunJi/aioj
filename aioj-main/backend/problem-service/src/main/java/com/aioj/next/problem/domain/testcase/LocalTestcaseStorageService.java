package com.aioj.next.problem.domain.testcase;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.problem.config.TestcaseProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class LocalTestcaseStorageService implements TestcaseStorageService {
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final Path root;

    public LocalTestcaseStorageService(TestcaseProperties properties) {
        this.root = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    @Override
    public StoredChunk writeStagingChunk(String uploadId, int index, InputStream input) {
        Path uploadDir = resolveStorageKey("tmp/" + uploadId);
        Path staging = uploadDir.resolve(index + ".part.upload-" + UUID.randomUUID()).normalize();
        ensureUnderRoot(staging);
        try {
            Files.createDirectories(uploadDir);
            DigestResult digest = writeWithDigest(input, staging);
            return new StoredChunk(staging, digest.sizeBytes(), digest.sha256());
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to write testcase chunk");
        }
    }

    @Override
    public String commitTempChunk(Path stagingPath, String uploadId, int index) {
        Path finalPath = resolveStorageKey(tempChunkStorageKey(uploadId, index));
        try {
            Files.createDirectories(finalPath.getParent());
            Files.move(stagingPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            return tempChunkStorageKey(uploadId, index);
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to store testcase chunk");
        }
    }

    @Override
    public MergeResult mergePackage(String uploadId, Long problemId, String sha256, int totalChunks) {
        String storageKey = packageStorageKey(problemId, sha256);
        Path packagePath = resolveStorageKey(storageKey);
        Path mergePath = packagePath.resolveSibling(packagePath.getFileName() + ".merge-" + UUID.randomUUID());
        ensureUnderRoot(mergePath);
        MessageDigest digest = sha256Digest();
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            Files.createDirectories(packagePath.getParent());
            try (OutputStream output = Files.newOutputStream(mergePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path chunk = resolveStorageKey(tempChunkStorageKey(uploadId, i));
                    if (!Files.isRegularFile(chunk)) {
                        throw new DomainException(ErrorCode.BAD_REQUEST, "Missing testcase upload chunk: " + i);
                    }
                    try (InputStream input = Files.newInputStream(chunk, StandardOpenOption.READ)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            digest.update(buffer, 0, read);
                            output.write(buffer, 0, read);
                            size += read;
                        }
                    }
                }
            }
            Files.move(mergePath, packagePath, StandardCopyOption.REPLACE_EXISTING);
            return new MergeResult(packagePath, storageKey, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException ex) {
            deleteIfExists(mergePath);
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to merge testcase package");
        } catch (RuntimeException ex) {
            deleteIfExists(mergePath);
            throw ex;
        }
    }

    @Override
    public Path resolveStorageKey(String storageKey) {
        Path resolved = root.resolve(storageKey).toAbsolutePath().normalize();
        ensureUnderRoot(resolved);
        return resolved;
    }

    @Override
    public String packageStorageKey(Long problemId, String sha256) {
        return "packages/" + problemId + "/" + sha256 + ".zip";
    }

    @Override
    public boolean exists(String storagePath) {
        return Files.isRegularFile(resolveStorageKey(storagePath));
    }

    @Override
    public void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        ensureUnderRoot(normalized);
        try {
            Files.deleteIfExists(normalized);
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    @Override
    public void deleteTempUpload(String uploadId) {
        Path uploadDir = resolveStorageKey("tmp/" + uploadId);
        if (!Files.exists(uploadDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(uploadDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteIfExists);
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private String tempChunkStorageKey(String uploadId, int index) {
        return "tmp/" + uploadId + "/" + index + ".part";
    }

    private DigestResult writeWithDigest(InputStream input, Path outputPath) throws IOException {
        MessageDigest digest = sha256Digest();
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream source = input;
             OutputStream output = Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                size += read;
            }
        }
        return new DigestResult(size, HexFormat.of().formatHex(digest.digest()));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "SHA-256 is not available");
        }
    }

    private void ensureUnderRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid testcase storage path");
        }
    }

    private record DigestResult(long sizeBytes, String sha256) {
    }
}
