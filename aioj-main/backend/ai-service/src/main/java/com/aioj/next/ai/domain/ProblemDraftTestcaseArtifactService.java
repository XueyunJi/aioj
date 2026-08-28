package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.ai.domain.problem.OfficialTestcasePackageReport;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseArtifactMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class ProblemDraftTestcaseArtifactService {
    public static final String STATUS_READY = "READY";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private final ProblemDraftTestcaseArtifactMapper mapper;
    private final ObjectMapper objectMapper;
    private final RestClient judgeWorkerClient;
    private final Path storageRoot;

    public ProblemDraftTestcaseArtifactService(ProblemDraftTestcaseArtifactMapper mapper,
                                               ObjectMapper objectMapper,
                                               AiProperties properties,
                                               InternalApiProperties internalApiProperties) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(properties.getProblemDraft().getTestcaseArtifactStorageRoot()).toAbsolutePath().normalize();
        this.judgeWorkerClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getJudgeWorkerUri()))
                .defaultHeader("X-Internal-Token", internalApiProperties.getApiToken())
                .build();
    }

    public ProblemDraftTestcaseArtifactEntity storeFromOfficialPackage(Long draftId, Long creatorUserId,
                                                                       OfficialTestcasePackageReport report) {
        if (draftId == null || report == null || !report.passed()) {
            return null;
        }
        if (!StringUtils.hasText(report.packageFileId())) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Official testcase package file id is missing");
        }
        try {
            Files.createDirectories(storageRoot);
            Path draftDir = storageRoot.resolve(String.valueOf(draftId)).normalize();
            ensureInsideStorage(draftDir);
            Files.createDirectories(draftDir);
            Path temp = Files.createTempFile(draftDir, "official-hidden-", ".zip.tmp");
            downloadPackage(report.packageFileId(), temp);
            long size = Files.size(temp);
            String sha256 = sha256(temp);
            if (report.packageFileSizeBytes() != null && report.packageFileSizeBytes() > 0
                    && report.packageFileSizeBytes() != size) {
                Files.deleteIfExists(temp);
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "Official testcase package size mismatch after download");
            }
            if (StringUtils.hasText(report.packageSha256())
                    && !report.packageSha256().equalsIgnoreCase(sha256)) {
                Files.deleteIfExists(temp);
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "Official testcase package SHA-256 mismatch after download");
            }
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftTestcaseArtifactEntity entity = new ProblemDraftTestcaseArtifactEntity();
            entity.setDraftId(draftId);
            entity.setCreatorUserId(creatorUserId);
            entity.setStatus(STATUS_READY);
            entity.setFileName(safeFileName(report.packageFileName()));
            entity.setStoragePath("");
            entity.setFileSizeBytes(size);
            entity.setSha256(sha256);
            entity.setCaseCount(report.caseCount() == null ? 0 : report.caseCount());
            entity.setTotalInputBytes(report.totalInputBytes() == null ? 0L : report.totalInputBytes());
            entity.setTotalOutputBytes(report.totalOutputBytes() == null ? 0L : report.totalOutputBytes());
            entity.setLargestCaseBytes(report.largestCaseBytes() == null ? 0L : report.largestCaseBytes());
            entity.setPackageSummaryJson(objectMapper.writeValueAsString(report));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);

            Path finalPath = draftDir.resolve(entity.getId() + ".zip").normalize();
            ensureInsideStorage(finalPath);
            Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING);
            entity.setStoragePath(storageRoot.relativize(finalPath).toString().replace('\\', '/'));
            mapper.updateById(entity);
            supersedeOlder(draftId, entity.getId());
            return entity;
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "Failed to persist official testcase package artifact: " + ex.getMessage());
        }
    }

    public ProblemDraftTestcaseArtifactEntity latestReady(Long draftId) {
        if (draftId == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<ProblemDraftTestcaseArtifactEntity>()
                .eq(ProblemDraftTestcaseArtifactEntity::getDraftId, draftId)
                .eq(ProblemDraftTestcaseArtifactEntity::getStatus, STATUS_READY)
                .orderByDesc(ProblemDraftTestcaseArtifactEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    public Path resolvePath(ProblemDraftTestcaseArtifactEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.getStoragePath())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact is missing");
        }
        Path path = storageRoot.resolve(entity.getStoragePath()).normalize();
        ensureInsideStorage(path);
        if (!Files.isRegularFile(path)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact file is missing");
        }
        return path;
    }

    public void markImported(Long artifactId, Long problemId, Long testcasePackageId) {
        if (artifactId == null) {
            return;
        }
        ProblemDraftTestcaseArtifactEntity entity = mapper.selectById(artifactId);
        if (entity == null) {
            return;
        }
        entity.setImportedProblemId(problemId);
        entity.setProblemTestcasePackageId(testcasePackageId);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
    }

    private void downloadPackage(String fileId, Path temp) {
        judgeWorkerClient.get()
                .uri("/api/v1/internal/sandbox/files/{fileId}", fileId)
                .header(HttpHeaders.ACCEPT, "application/octet-stream")
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                                "Failed to download official testcase package from judge-worker");
                    }
                    try (InputStream input = response.getBody()) {
                        if (input == null) {
                            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                                    "Official testcase package download returned empty body");
                        }
                        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ex) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                                "Failed to store official testcase package download");
                    }
                    return null;
                });
    }

    private void supersedeOlder(Long draftId, Long keepId) {
        mapper.update(new ProblemDraftTestcaseArtifactEntity(),
                new LambdaUpdateWrapper<ProblemDraftTestcaseArtifactEntity>()
                        .eq(ProblemDraftTestcaseArtifactEntity::getDraftId, draftId)
                        .eq(ProblemDraftTestcaseArtifactEntity::getStatus, STATUS_READY)
                        .ne(ProblemDraftTestcaseArtifactEntity::getId, keepId)
                        .set(ProblemDraftTestcaseArtifactEntity::getStatus, STATUS_SUPERSEDED)
                        .set(ProblemDraftTestcaseArtifactEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void ensureInsideStorage(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid testcase artifact storage path");
        }
    }

    private String safeFileName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "official-hidden-testcases.zip";
        name = name.replace('\r', '_').replace('\n', '_').replace('/', '_').replace('\\', '_');
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name += ".zip";
        }
        return name;
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8203";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
