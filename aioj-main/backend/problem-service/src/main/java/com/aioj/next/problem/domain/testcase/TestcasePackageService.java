package com.aioj.next.problem.domain.testcase;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.problem.TestcasePackageCaseResponse;
import com.aioj.next.contract.problem.TestcasePackageCheckerResponse;
import com.aioj.next.contract.problem.TestcasePackageResponse;
import com.aioj.next.contract.problem.TestcasePackageStatus;
import com.aioj.next.contract.problem.TestcasePackageSubtaskResponse;
import com.aioj.next.contract.problem.TestcaseCheckerProtocol;
import com.aioj.next.contract.problem.TestcaseCheckerType;
import com.aioj.next.contract.problem.TestcaseUploadCompleteRequest;
import com.aioj.next.contract.problem.TestcaseUploadFailRequest;
import com.aioj.next.contract.problem.TestcaseUploadInitRequest;
import com.aioj.next.contract.problem.TestcaseUploadInitResponse;
import com.aioj.next.contract.problem.TestcaseUploadStatusResponse;
import com.aioj.next.problem.config.TestcaseProperties;
import com.aioj.next.problem.domain.OperationAuditService;
import com.aioj.next.problem.domain.ProblemCatalog;
import com.aioj.next.problem.persistence.entity.ProblemSubtaskEntity;
import com.aioj.next.problem.persistence.entity.TestcasePackageCaseEntity;
import com.aioj.next.problem.persistence.entity.TestcasePackageEntity;
import com.aioj.next.problem.persistence.entity.TestcaseUploadChunkEntity;
import com.aioj.next.problem.persistence.entity.TestcaseUploadSessionEntity;
import com.aioj.next.problem.persistence.mapper.ProblemSubtaskMapper;
import com.aioj.next.problem.persistence.mapper.TestcasePackageCaseMapper;
import com.aioj.next.problem.persistence.mapper.TestcasePackageMapper;
import com.aioj.next.problem.persistence.mapper.TestcaseUploadChunkMapper;
import com.aioj.next.problem.persistence.mapper.TestcaseUploadSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Service
public class TestcasePackageService {
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_TOTAL_CHUNKS = 10_000;
    private static final int ERROR_LIMIT = 1_000;
    private static final String PENDING_VERSION = "pending";
    private static final String MANIFEST_PATH = "manifest.json";
    private static final int ZIP_BUFFER_SIZE = 8192;
    private static final Pattern SAFE_ENTRY_SEGMENT = Pattern.compile("[^a-zA-Z0-9._-]+");
    private static final DateTimeFormatter VERSION_SUFFIX_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final ProblemCatalog problemCatalog;
    private final TestcaseProperties properties;
    private final TestcaseStorageService storageService;
    private final TestcasePackageValidator validator;
    private final TestcasePackageMapper packageMapper;
    private final TestcasePackageCaseMapper caseMapper;
    private final ProblemSubtaskMapper subtaskMapper;
    private final TestcaseUploadSessionMapper sessionMapper;
    private final TestcaseUploadChunkMapper chunkMapper;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;

    public TestcasePackageService(ProblemCatalog problemCatalog,
                                  TestcaseProperties properties,
                                  TestcaseStorageService storageService,
                                  TestcasePackageValidator validator,
                                  TestcasePackageMapper packageMapper,
                                  TestcasePackageCaseMapper caseMapper,
                                  ProblemSubtaskMapper subtaskMapper,
                                  TestcaseUploadSessionMapper sessionMapper,
                                  TestcaseUploadChunkMapper chunkMapper,
                                  OperationAuditService auditService,
                                  ObjectMapper objectMapper) {
        this.problemCatalog = problemCatalog;
        this.properties = properties;
        this.storageService = storageService;
        this.validator = validator;
        this.packageMapper = packageMapper;
        this.caseMapper = caseMapper;
        this.subtaskMapper = subtaskMapper;
        this.sessionMapper = sessionMapper;
        this.chunkMapper = chunkMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TestcaseUploadInitResponse init(Long problemId, TestcaseUploadInitRequest request) {
        requireProblem(problemId);
        InitSpec spec = validateInit(request);
        TestcasePackageEntity existing = findPackageBySha(problemId, spec.sha256());
        if (existing != null && existing.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.CONFLICT, "A deleted testcase package with the same SHA-256 already exists");
        }
        if (existing != null && existing.getStatus() == TestcasePackageStatus.READY) {
            TestcaseUploadSessionEntity session = createSession(problemId, spec, existing.getId(),
                    TestcasePackageStatus.READY, spec.totalChunks(), null);
            return toInitResponse(session, "Testcase package already exists");
        }
        if (existing != null && existing.getStatus() == TestcasePackageStatus.PROCESSING) {
            throw new DomainException(ErrorCode.CONFLICT, "Testcase package is still processing");
        }
        if (existing != null && existing.getStatus() == TestcasePackageStatus.UPLOADING) {
            TestcaseUploadSessionEntity session = latestSession(problemId, spec.sha256(), existing.getId());
            if (session != null) {
                return toInitResponse(session, "Testcase upload already initialized");
            }
        }

        TestcasePackageEntity testcasePackage = existing == null
                ? createPackage(problemId, spec)
                : resetFailedPackage(existing, spec);
        TestcaseUploadSessionEntity session = createSession(problemId, spec, testcasePackage.getId(),
                TestcasePackageStatus.UPLOADING, 0, null);
        return toInitResponse(session, "Testcase upload initialized");
    }

    public TestcaseUploadStatusResponse uploadChunk(Long problemId, String uploadId, int index,
                                                    String chunkSha256Header, InputStream input) {
        TestcaseUploadSessionEntity session = requireUploadSession(problemId, uploadId);
        assertUploading(session);
        if (index < 0 || index >= session.getTotalChunks()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Chunk index is out of range");
        }
        String expectedChunkSha = normalizeOptionalSha(chunkSha256Header, "X-Chunk-Sha256");
        TestcaseStorageService.StoredChunk staged = storageService.writeStagingChunk(uploadId, index, input);
        try {
            validateChunkSize(session, index, staged.sizeBytes());
            if (expectedChunkSha != null && !expectedChunkSha.equals(staged.sha256())) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Chunk SHA-256 does not match X-Chunk-Sha256");
            }

            TestcaseUploadChunkEntity existing = findChunk(uploadId, index);
            if (existing != null) {
                if (!existing.getChunkSizeBytes().equals(staged.sizeBytes()) || !existing.getSha256().equals(staged.sha256())) {
                    throw new DomainException(ErrorCode.CONFLICT, "Uploaded chunk differs from existing chunk");
                }
                storageService.commitTempChunk(staged.stagingPath(), uploadId, index);
                refreshUploadedChunkCount(session);
                return status(problemId, uploadId);
            }

            String storagePath = storageService.commitTempChunk(staged.stagingPath(), uploadId, index);
            TestcaseUploadChunkEntity chunk = new TestcaseUploadChunkEntity();
            chunk.setUploadId(uploadId);
            chunk.setChunkIndex(index);
            chunk.setChunkSizeBytes(staged.sizeBytes());
            chunk.setSha256(staged.sha256());
            chunk.setStoragePath(storagePath);
            chunk.setCreatedAt(Instant.now());
            chunkMapper.insert(chunk);
            refreshUploadedChunkCount(session);
            return status(problemId, uploadId);
        } catch (RuntimeException ex) {
            storageService.deleteIfExists(staged.stagingPath());
            throw ex;
        }
    }

    public TestcasePackageResponse complete(Long problemId, String uploadId, TestcaseUploadCompleteRequest request) {
        TestcaseUploadSessionEntity session = requireUploadSession(problemId, uploadId);
        if (session.getStatus() == TestcasePackageStatus.READY) {
            return toResponse(requirePackage(session.getPackageId()));
        }
        assertUploading(session);
        List<TestcaseUploadChunkEntity> chunks = chunks(uploadId);
        verifyCompleteChunks(session, chunks);
        markProcessing(session);
        try {
            TestcaseStorageService.MergeResult merged = storageService.mergePackage(uploadId, problemId,
                    session.getSha256(), session.getTotalChunks());
            if (merged.sizeBytes() != session.getFileSizeBytes()) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Merged testcase package size does not match init request");
            }
            if (!merged.sha256().equals(session.getSha256())) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Merged testcase package SHA-256 does not match init request");
            }
            TestcasePackageValidator.ValidatedPackage validated = validator.validate(merged.packagePath());
            saveReadyPackage(session, merged, validated);
            storageService.deleteTempUpload(uploadId);
            return toResponse(requirePackage(session.getPackageId()));
        } catch (RuntimeException ex) {
            markFailed(session, userMessage(ex));
            if (ex instanceof DomainException domainException) {
                throw domainException;
            }
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to process testcase package");
        }
    }

    @Transactional
    public TestcaseUploadStatusResponse failUpload(Long problemId, String uploadId, TestcaseUploadFailRequest request) {
        TestcaseUploadSessionEntity session = requireUploadSession(problemId, uploadId);
        if (session.getStatus() == TestcasePackageStatus.UPLOADING) {
            markFailed(session, failMessage(request));
            storageService.deleteTempUpload(uploadId);
        }
        return toUploadStatusResponse(session);
    }

    public TestcaseUploadStatusResponse status(Long problemId, String uploadId) {
        TestcaseUploadSessionEntity session = requireUploadSession(problemId, uploadId);
        return toUploadStatusResponse(session);
    }

    private TestcaseUploadStatusResponse toUploadStatusResponse(TestcaseUploadSessionEntity session) {
        List<Integer> uploadedChunks = session.getStatus() == TestcasePackageStatus.READY
                ? fullChunkList(session.getTotalChunks())
                : uploadedChunkIndexes(session.getId());
        double progress = session.getTotalChunks() == null || session.getTotalChunks() == 0
                ? 0.0D
                : (double) uploadedChunks.size() / session.getTotalChunks();
        return new TestcaseUploadStatusResponse(session.getId(), session.getStatus(), uploadedChunks,
                session.getTotalChunks(), progress, session.getPackageId(), session.getErrorMessage());
    }

    public List<TestcasePackageResponse> list(Long problemId) {
        requireProblem(problemId);
        return packageMapper.selectList(new LambdaQueryWrapper<TestcasePackageEntity>()
                        .eq(TestcasePackageEntity::getProblemId, problemId)
                        .isNull(TestcasePackageEntity::getDeletedAt)
                        .orderByAsc(TestcasePackageEntity::getCreatedAt)
                        .orderByAsc(TestcasePackageEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TestcasePackageResponse appendCase(Long problemId, Long packageId, MultipartFile inputFile,
                                              MultipartFile outputFile, String caseName, Integer score,
                                              String subtaskKey) {
        return appendCases(problemId, packageId,
                List.of(new AppendCaseMetadata(caseName, score, subtaskKey)),
                inputFile == null ? List.of() : List.of(inputFile),
                outputFile == null ? List.of() : List.of(outputFile));
    }

    @Transactional
    public TestcasePackageResponse appendCases(Long problemId, Long packageId, String metadataJson,
                                               List<MultipartFile> inputFiles,
                                               List<MultipartFile> outputFiles) {
        AppendCasesMetadata metadata = parseAppendCasesMetadata(metadataJson);
        if (metadata == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "append case metadata is invalid");
        }
        return appendCases(problemId, packageId, metadata.cases(), inputFiles, outputFiles);
    }

    private TestcasePackageResponse appendCases(Long problemId, Long packageId,
                                                List<AppendCaseMetadata> metadataCases,
                                                List<MultipartFile> inputFiles,
                                                List<MultipartFile> outputFiles) {
        requireProblem(problemId);
        TestcasePackageEntity sourcePackage = requirePackage(problemId, packageId);
        assertAppendable(sourcePackage);
        List<AppendCaseSpec> specs = validateAppendCases(sourcePackage, metadataCases, inputFiles, outputFiles);

        Path sourcePath = storageService.resolveStorageKey(sourcePackage.getStorageKey());
        if (!Files.isRegularFile(sourcePath)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Source testcase package zip not found");
        }

        Path tempZip = null;
        try {
            tempZip = Files.createTempFile("aioj-testcase-append-", ".zip");
            String generatedVersion = generatedAppendVersion(sourcePackage.getVersion());
            buildAppendedZip(sourcePackage, sourcePath, tempZip, generatedVersion, specs);
            TestcasePackageValidator.ValidatedPackage validated = validator.validate(tempZip);
            TestcaseStorageService.MergeResult stored = storeGeneratedPackage(problemId, tempZip);
            TestcasePackageEntity targetPackage = createGeneratedPackage(sourcePackage, stored);
            saveValidatedPackage(targetPackage.getId(), problemId, stored, validated);
            if (Boolean.TRUE.equals(sourcePackage.getActive())) {
                activateGeneratedPackage(problemId, targetPackage.getId());
            }
            auditService.recordCurrentUser("APPEND_TESTCASE_CASES", "TESTCASE_PACKAGE", targetPackage.getId(),
                    null, null, null, "SUCCESS", Map.of(
                            "problemId", problemId,
                            "sourcePackageId", packageId,
                            "targetPackageId", targetPackage.getId(),
                            "caseCount", specs.size(),
                            "caseNames", specs.stream().map(AppendCaseSpec::caseName).toList()));
            return toResponse(requirePackage(targetPackage.getId()));
        } catch (DomainException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to append testcase case");
        } finally {
            deleteTempFile(tempZip);
        }
    }

    private AppendCasesMetadata parseAppendCasesMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "append case metadata is required");
        }
        try {
            return objectMapper.readValue(metadataJson, AppendCasesMetadata.class);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "append case metadata is invalid");
        }
    }

    public TestcasePackageEntity findReadyOrThrow(Long packageId) {
        TestcasePackageEntity testcasePackage = requirePackage(packageId);
        if (testcasePackage.getStatus() != TestcasePackageStatus.READY) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only READY testcase packages can be downloaded");
        }
        return testcasePackage;
    }

    public TestcasePackageEntity findDownloadable(Long problemId, Long packageId) {
        requireProblem(problemId);
        TestcasePackageEntity testcasePackage = requirePackage(problemId, packageId);
        if (testcasePackage.getStatus() != TestcasePackageStatus.READY) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only READY testcase packages can be downloaded");
        }
        if (!StringUtils.hasText(testcasePackage.getStorageKey())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Testcase package zip not found");
        }
        return testcasePackage;
    }

    @Transactional
    public TestcasePackageResponse activate(Long problemId, Long packageId) {
        requireProblem(problemId);
        TestcasePackageEntity testcasePackage = requirePackage(problemId, packageId);
        if (testcasePackage.getStatus() != TestcasePackageStatus.READY) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only READY testcase packages can be activated");
        }
        if (testcasePackage.getArchivedAt() != null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived testcase packages cannot be activated");
        }
        Instant now = Instant.now();
        packageMapper.update(new TestcasePackageEntity(), new LambdaUpdateWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getProblemId, problemId)
                .eq(TestcasePackageEntity::getActive, true)
                .set(TestcasePackageEntity::getActive, false)
                .set(TestcasePackageEntity::getUpdatedAt, now));
        packageMapper.update(new TestcasePackageEntity(), new LambdaUpdateWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getId, packageId)
                .eq(TestcasePackageEntity::getProblemId, problemId)
                .set(TestcasePackageEntity::getActive, true)
                .set(TestcasePackageEntity::getActivatedAt, now)
                .set(TestcasePackageEntity::getUpdatedAt, now));
        return toResponse(requirePackage(packageId));
    }

    @Transactional
    public TestcasePackageResponse archive(Long problemId, Long packageId) {
        requireProblem(problemId);
        TestcasePackageEntity testcasePackage = requirePackage(problemId, packageId);
        assertCanArchiveOrDelete(testcasePackage);
        testcasePackage.setArchivedAt(Instant.now());
        testcasePackage.setUpdatedAt(Instant.now());
        packageMapper.updateById(testcasePackage);
        auditService.recordCurrentUser("ARCHIVE", "TESTCASE_PACKAGE", testcasePackage.getId(), null, null, null,
                "SUCCESS", Map.of("problemId", problemId));
        return toResponse(testcasePackage);
    }

    @Transactional
    public TestcasePackageResponse restore(Long problemId, Long packageId) {
        requireProblem(problemId);
        TestcasePackageEntity testcasePackage = requirePackage(problemId, packageId);
        if (testcasePackage.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived testcase packages can be restored");
        }
        Instant now = Instant.now();
        packageMapper.update(new TestcasePackageEntity(), new UpdateWrapper<TestcasePackageEntity>()
                .eq("id", testcasePackage.getId())
                .eq("problem_id", problemId)
                .set("archived_at", null)
                .set("updated_at", now));
        testcasePackage.setArchivedAt(null);
        testcasePackage.setUpdatedAt(now);
        auditService.recordCurrentUser("RESTORE", "TESTCASE_PACKAGE", testcasePackage.getId(), null, null, null,
                "SUCCESS", Map.of("problemId", problemId));
        return toResponse(testcasePackage);
    }

    @Transactional
    public void softDelete(Long problemId, Long packageId) {
        requireProblem(problemId);
        TestcasePackageEntity testcasePackage = requirePackage(problemId, packageId);
        if (testcasePackage.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived testcase packages can be deleted");
        }
        assertCanArchiveOrDelete(testcasePackage);
        testcasePackage.setDeletedAt(Instant.now());
        testcasePackage.setDeletedBy(SecuritySupport.currentUserId());
        testcasePackage.setUpdatedAt(Instant.now());
        packageMapper.updateById(testcasePackage);
        auditService.recordCurrentUser("SOFT_DELETE", "TESTCASE_PACKAGE", testcasePackage.getId(), null, null, null,
                "SUCCESS", Map.of("problemId", problemId));
    }

    private void assertAppendable(TestcasePackageEntity testcasePackage) {
        if (testcasePackage.getStatus() != TestcasePackageStatus.READY) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only READY testcase packages can accept appended cases");
        }
        if (testcasePackage.getArchivedAt() != null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived testcase packages cannot accept appended cases");
        }
        if (!StringUtils.hasText(testcasePackage.getStorageKey())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Source testcase package zip not found");
        }
    }

    private List<AppendCaseSpec> validateAppendCases(TestcasePackageEntity sourcePackage,
                                                     List<AppendCaseMetadata> metadataCases,
                                                     List<MultipartFile> inputFiles,
                                                     List<MultipartFile> outputFiles) {
        List<AppendCaseMetadata> cases = metadataCases == null ? List.of() : metadataCases;
        List<MultipartFile> inputs = inputFiles == null ? List.of() : inputFiles;
        List<MultipartFile> outputs = outputFiles == null ? List.of() : outputFiles;
        if (cases.isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "At least one testcase case is required");
        }
        if (cases.size() != inputs.size() || cases.size() != outputs.size()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "append case metadata and file counts do not match");
        }
        List<ProblemSubtaskEntity> subtasks = packageSubtasks(sourcePackage.getId());
        Set<String> usedCaseNames = new HashSet<>();
        for (TestcasePackageCaseEntity entity : packageCases(sourcePackage.getId())) {
            usedCaseNames.add(caseNameKey(entity.getName()));
        }
        List<PendingAppendCase> pendingCases = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            AppendCaseMetadata item = cases.get(index);
            String normalizedName = item == null || item.caseName() == null ? "" : item.caseName().trim();
            if (!StringUtils.hasText(normalizedName) || normalizedName.length() > 160) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase case name is required and must be at most 160 characters");
            }
            String caseNameKey = caseNameKey(normalizedName);
            if (!usedCaseNames.add(caseNameKey)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase case name must be unique");
            }
            int normalizedScore = item.score() == null ? 1 : item.score();
            if (normalizedScore < 0) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase case score must be non-negative");
            }
            MultipartFile inputFile = inputs.get(index);
            MultipartFile outputFile = outputs.get(index);
            assertMultipartFile(inputFile, ".in", "inputFiles");
            assertMultipartFile(outputFile, ".out", "outputFiles");
            String normalizedSubtaskKey = normalizeAppendSubtask(subtasks, item.subtaskKey());
            pendingCases.add(new PendingAppendCase(normalizedName, normalizedScore, normalizedSubtaskKey,
                    inputFile, outputFile));
        }
        Set<String> usedEntries = existingEntryNames(sourcePackage);
        List<AppendCaseSpec> specs = new ArrayList<>();
        for (PendingAppendCase item : pendingCases) {
            String baseName = safeEntrySegment(item.caseName());
            String inputPath = uniqueEntryPath(usedEntries, "manual/" + baseName, ".in");
            usedEntries.add(inputPath);
            String outputPath = uniqueEntryPath(usedEntries, "manual/" + baseName, ".out");
            usedEntries.add(outputPath);
            specs.add(new AppendCaseSpec(item.caseName(), item.score(), item.subtaskKey(), inputPath, outputPath,
                    item.inputFile(), item.outputFile()));
        }
        return specs;
    }

    private String caseNameKey(String caseName) {
        return caseName == null ? "" : caseName.trim().toLowerCase(Locale.ROOT);
    }

    private void assertMultipartFile(MultipartFile file, String expectedExtension, String fieldName) {
        if (file == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, fieldName + " is required");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        if (!StringUtils.hasText(originalName)
                || !originalName.toLowerCase(Locale.ROOT).endsWith(expectedExtension)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, fieldName + " must be a " + expectedExtension + " file");
        }
        if (file.getSize() > properties.getMaxCaseFileBytes()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, fieldName + " exceeds testcase file size limit");
        }
    }

    private String normalizeAppendSubtask(List<ProblemSubtaskEntity> subtasks, String subtaskKey) {
        if (subtasks.isEmpty()) {
            return null;
        }
        String normalized = subtaskKey == null ? "" : subtaskKey.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "subtaskKey is required for testcase packages with subtasks");
        }
        boolean found = subtasks.stream().anyMatch(item -> normalized.equals(item.getSubtaskKey()));
        if (!found) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "subtaskKey does not exist in the source testcase package");
        }
        return normalized;
    }

    private Set<String> existingEntryNames(TestcasePackageEntity sourcePackage) {
        Path sourcePath = storageService.resolveStorageKey(sourcePackage.getStorageKey());
        Set<String> names = new HashSet<>();
        if (!Files.isRegularFile(sourcePath)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Source testcase package zip not found");
        }
        try (ZipFile zipFile = new ZipFile(sourcePath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                names.add(normalizeZipEntryName(entries.nextElement().getName()));
            }
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Source testcase package zip cannot be read");
        }
        return names;
    }

    private void buildAppendedZip(TestcasePackageEntity sourcePackage, Path sourcePath, Path targetPath,
                                  String generatedVersion, List<AppendCaseSpec> specs) throws IOException {
        TestcasePackageValidator.ManifestPayload manifest = buildGeneratedManifest(sourcePackage, generatedVersion, specs);
        try (ZipFile sourceZip = new ZipFile(sourcePath.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(targetPath,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            copyOriginalZipEntries(sourceZip, output);
            for (AppendCaseSpec spec : specs) {
                writeMultipartEntry(output, spec.inputPath(), spec.inputFile());
                writeMultipartEntry(output, spec.outputPath(), spec.outputFile());
            }
            writeManifestEntry(output, manifest);
        }
    }

    private TestcasePackageValidator.ManifestPayload buildGeneratedManifest(TestcasePackageEntity sourcePackage,
                                                                            String generatedVersion,
                                                                            List<AppendCaseSpec> specs) {
        List<TestcasePackageValidator.ManifestSubtask> subtasks = new ArrayList<>();
        for (ProblemSubtaskEntity entity : packageSubtasks(sourcePackage.getId())) {
            subtasks.add(new TestcasePackageValidator.ManifestSubtask(entity.getSubtaskKey(), entity.getTitle(),
                    entity.getScore(), entity.getSortOrder()));
        }
        List<TestcasePackageValidator.ManifestCase> cases = new ArrayList<>();
        for (TestcasePackageCaseEntity entity : packageCases(sourcePackage.getId())) {
            cases.add(new TestcasePackageValidator.ManifestCase(entity.getName(), entity.getInputPath(),
                    entity.getOutputPath(), Boolean.TRUE.equals(entity.getSample()), entity.getSubtaskKey(),
                    entity.getScore()));
        }
        for (AppendCaseSpec spec : specs) {
            cases.add(new TestcasePackageValidator.ManifestCase(spec.caseName(), spec.inputPath(), spec.outputPath(),
                    false, spec.subtaskKey(), spec.score()));
        }

        TestcaseCheckerType checkerType = sourcePackage.getCheckerType() == null
                ? TestcaseCheckerType.STANDARD
                : sourcePackage.getCheckerType();
        TestcaseCheckerProtocol checkerProtocol = sourcePackage.getCheckerProtocol() == null
                ? TestcaseCheckerProtocol.AIOJ_JSON
                : sourcePackage.getCheckerProtocol();
        TestcasePackageValidator.ManifestChecker checker = new TestcasePackageValidator.ManifestChecker(
                checkerType.name(), sourcePackage.getCheckerLanguage(), sourcePackage.getCheckerSourcePath(),
                checkerProtocol.name());
        return new TestcasePackageValidator.ManifestPayload(generatedVersion, checker, subtasks, cases);
    }

    private void copyOriginalZipEntries(ZipFile sourceZip, ZipOutputStream output) throws IOException {
        byte[] buffer = new byte[ZIP_BUFFER_SIZE];
        Enumeration<? extends ZipEntry> entries = sourceZip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = normalizeZipEntryName(entry.getName());
            if (MANIFEST_PATH.equals(name)) {
                continue;
            }
            ZipEntry copy = new ZipEntry(name);
            if (entry.getTime() >= 0) {
                copy.setTime(entry.getTime());
            }
            output.putNextEntry(copy);
            if (!entry.isDirectory()) {
                try (InputStream input = sourceZip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }
            output.closeEntry();
        }
    }

    private void writeMultipartEntry(ZipOutputStream output, String entryPath, MultipartFile multipartFile)
            throws IOException {
        output.putNextEntry(new ZipEntry(entryPath));
        try (InputStream input = multipartFile.getInputStream()) {
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private void writeManifestEntry(ZipOutputStream output,
                                    TestcasePackageValidator.ManifestPayload manifest) throws IOException {
        output.putNextEntry(new ZipEntry(MANIFEST_PATH));
        output.write(objectMapper.writeValueAsBytes(manifest));
        output.closeEntry();
    }

    private TestcaseStorageService.MergeResult storeGeneratedPackage(Long problemId, Path tempZip) throws IOException {
        long sizeBytes = Files.size(tempZip);
        if (sizeBytes <= 0 || sizeBytes > properties.getMaxPackageBytes()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Generated testcase package file size exceeds limit");
        }
        String sha256 = sha256(tempZip);
        String storageKey = storageService.packageStorageKey(problemId, sha256);
        Path packagePath = storageService.resolveStorageKey(storageKey);
        Path parent = packagePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(tempZip, packagePath, StandardCopyOption.REPLACE_EXISTING);
        return new TestcaseStorageService.MergeResult(packagePath, storageKey, sizeBytes, sha256);
    }

    private TestcasePackageEntity createGeneratedPackage(TestcasePackageEntity sourcePackage,
                                                        TestcaseStorageService.MergeResult stored) {
        Instant now = Instant.now();
        TestcasePackageEntity generated = new TestcasePackageEntity();
        generated.setProblemId(sourcePackage.getProblemId());
        generated.setVersion(PENDING_VERSION);
        generated.setFileName(generatedFileName(sourcePackage.getFileName()));
        generated.setFileSizeBytes(stored.sizeBytes());
        generated.setSha256(stored.sha256());
        generated.setStatus(TestcasePackageStatus.PROCESSING);
        generated.setActive(false);
        generated.setStorageProvider(TestcaseStorageService.LOCAL_PROVIDER);
        generated.setStorageKey(stored.storageKey());
        generated.setCaseCount(0);
        generated.setSampleCount(0);
        generated.setCheckerType(sourcePackage.getCheckerType() == null
                ? TestcaseCheckerType.STANDARD
                : sourcePackage.getCheckerType());
        generated.setCheckerLanguage(sourcePackage.getCheckerLanguage());
        generated.setCheckerSourcePath(sourcePackage.getCheckerSourcePath());
        generated.setCheckerProtocol(sourcePackage.getCheckerProtocol());
        generated.setCreatedBy(SecuritySupport.currentUserId());
        generated.setCreatedAt(now);
        generated.setUpdatedAt(now);
        packageMapper.insert(generated);
        return generated;
    }

    private void activateGeneratedPackage(Long problemId, Long targetPackageId) {
        Instant now = Instant.now();
        packageMapper.update(new TestcasePackageEntity(), new LambdaUpdateWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getProblemId, problemId)
                .eq(TestcasePackageEntity::getActive, true)
                .set(TestcasePackageEntity::getActive, false)
                .set(TestcasePackageEntity::getUpdatedAt, now));
        packageMapper.update(new TestcasePackageEntity(), new LambdaUpdateWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getId, targetPackageId)
                .eq(TestcasePackageEntity::getProblemId, problemId)
                .set(TestcasePackageEntity::getActive, true)
                .set(TestcasePackageEntity::getActivatedAt, now)
                .set(TestcasePackageEntity::getUpdatedAt, now));
    }

    private String generatedAppendVersion(String baseVersion) {
        String base = StringUtils.hasText(baseVersion) ? baseVersion.trim() : "v";
        String candidate = base + "-plus-" + VERSION_SUFFIX_FORMATTER.format(Instant.now());
        if (candidate.length() <= 64) {
            return candidate;
        }
        return "v-plus-" + Instant.now().toEpochMilli();
    }

    private String generatedFileName(String baseFileName) {
        String normalized = StringUtils.hasText(baseFileName) ? baseFileName.trim() : "testcases.zip";
        String stem = normalized.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? normalized.substring(0, normalized.length() - 4)
                : normalized;
        stem = safeEntrySegment(stem);
        String fileName = stem + "-plus.zip";
        if (fileName.length() <= 255) {
            return fileName;
        }
        return "testcases-plus.zip";
    }

    private String uniqueEntryPath(Set<String> usedEntries, String basePathWithoutExtension, String extension) {
        String candidate = basePathWithoutExtension + extension;
        int suffix = 2;
        while (usedEntries.contains(candidate)) {
            candidate = basePathWithoutExtension + "-" + suffix + extension;
            suffix++;
        }
        return candidate;
    }

    private String safeEntrySegment(String raw) {
        String normalized = raw == null ? "" : SAFE_ENTRY_SEGMENT.matcher(raw.trim()).replaceAll("_");
        normalized = normalized.replaceAll("^_+", "").replaceAll("_+$", "");
        if (!StringUtils.hasText(normalized)) {
            normalized = "case";
        }
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80);
        }
        return normalized;
    }

    private String normalizeZipEntryName(String raw) {
        String normalized = raw == null ? "" : raw.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!StringUtils.hasText(normalized)
                || normalized.contains("\0")
                || normalized.contains("../")
                || normalized.startsWith("..")) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase package contains an unsafe zip entry");
        }
        return normalized;
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[ZIP_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "SHA-256 algorithm is not available");
        }
    }

    private void deleteTempFile(Path tempZip) {
        if (tempZip == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempZip);
        } catch (IOException ignored) {
            // Best-effort cleanup for generated temporary zip files.
        }
    }

    private InitSpec validateInit(TestcaseUploadInitRequest request) {
        String fileName = normalizeFileName(request.fileName());
        String sha256 = normalizeSha(request.sha256(), "sha256");
        if (request.fileSizeBytes() <= 0 || request.fileSizeBytes() > properties.getMaxPackageBytes()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase package file size exceeds limit");
        }
        if (request.chunkSizeBytes() <= 0 || request.chunkSizeBytes() > properties.getChunkSizeBytes()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Chunk size exceeds configured limit");
        }
        long expectedChunks = (request.fileSizeBytes() + request.chunkSizeBytes() - 1) / request.chunkSizeBytes();
        if (expectedChunks <= 0 || expectedChunks > MAX_TOTAL_CHUNKS || expectedChunks != request.totalChunks()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Total chunks does not match file size and chunk size");
        }
        return new InitSpec(fileName, request.fileSizeBytes(), sha256, request.chunkSizeBytes(), request.totalChunks());
    }

    private String normalizeFileName(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > 255) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase package file name is required");
        }
        if (normalized.contains("/") || normalized.contains("\\") || !normalized.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only .zip testcase packages are supported");
        }
        return normalized;
    }

    private String normalizeSha(String sha256, String fieldName) {
        String normalized = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, fieldName + " must be a 64-character SHA-256 hex string");
        }
        return normalized;
    }

    private String normalizeOptionalSha(String sha256, String fieldName) {
        if (!StringUtils.hasText(sha256)) {
            return null;
        }
        return normalizeSha(sha256, fieldName);
    }

    private TestcasePackageEntity createPackage(Long problemId, InitSpec spec) {
        Instant now = Instant.now();
        TestcasePackageEntity testcasePackage = new TestcasePackageEntity();
        testcasePackage.setProblemId(problemId);
        testcasePackage.setVersion(PENDING_VERSION);
        testcasePackage.setFileName(spec.fileName());
        testcasePackage.setFileSizeBytes(spec.fileSizeBytes());
        testcasePackage.setSha256(spec.sha256());
        testcasePackage.setStatus(TestcasePackageStatus.UPLOADING);
        testcasePackage.setActive(false);
        testcasePackage.setStorageProvider(TestcaseStorageService.LOCAL_PROVIDER);
        testcasePackage.setStorageKey(storageService.packageStorageKey(problemId, spec.sha256()));
        testcasePackage.setCaseCount(0);
        testcasePackage.setSampleCount(0);
        testcasePackage.setCheckerType(TestcaseCheckerType.STANDARD);
        testcasePackage.setCreatedBy(SecuritySupport.currentUserId());
        testcasePackage.setCreatedAt(now);
        testcasePackage.setUpdatedAt(now);
        packageMapper.insert(testcasePackage);
        return testcasePackage;
    }

    private TestcasePackageEntity resetFailedPackage(TestcasePackageEntity existing, InitSpec spec) {
        Instant now = Instant.now();
        packageMapper.update(new TestcasePackageEntity(), new LambdaUpdateWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getId, existing.getId())
                .set(TestcasePackageEntity::getVersion, PENDING_VERSION)
                .set(TestcasePackageEntity::getFileName, spec.fileName())
                .set(TestcasePackageEntity::getFileSizeBytes, spec.fileSizeBytes())
                .set(TestcasePackageEntity::getStatus, TestcasePackageStatus.UPLOADING)
                .set(TestcasePackageEntity::getActive, false)
                .set(TestcasePackageEntity::getStorageProvider, TestcaseStorageService.LOCAL_PROVIDER)
                .set(TestcasePackageEntity::getStorageKey, storageService.packageStorageKey(existing.getProblemId(), spec.sha256()))
                .set(TestcasePackageEntity::getCaseCount, 0)
                .set(TestcasePackageEntity::getSampleCount, 0)
                .set(TestcasePackageEntity::getManifestJson, null)
                .set(TestcasePackageEntity::getCheckerType, TestcaseCheckerType.STANDARD)
                .set(TestcasePackageEntity::getCheckerLanguage, null)
                .set(TestcasePackageEntity::getCheckerSourcePath, null)
                .set(TestcasePackageEntity::getCheckerProtocol, null)
                .set(TestcasePackageEntity::getActivatedAt, null)
                .set(TestcasePackageEntity::getArchivedAt, null)
                .set(TestcasePackageEntity::getDeletedAt, null)
                .set(TestcasePackageEntity::getDeletedBy, null)
                .set(TestcasePackageEntity::getErrorMessage, null)
                .set(TestcasePackageEntity::getUpdatedAt, now));
        caseMapper.delete(new LambdaQueryWrapper<TestcasePackageCaseEntity>()
                .eq(TestcasePackageCaseEntity::getPackageId, existing.getId()));
        subtaskMapper.delete(new LambdaQueryWrapper<ProblemSubtaskEntity>()
                .eq(ProblemSubtaskEntity::getTestcasePackageId, existing.getId()));
        return requirePackage(existing.getId());
    }

    private TestcaseUploadSessionEntity createSession(Long problemId, InitSpec spec, Long packageId,
                                                       TestcasePackageStatus status, int uploadedChunks,
                                                       String tempDir) {
        Instant now = Instant.now();
        TestcaseUploadSessionEntity session = new TestcaseUploadSessionEntity();
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        session.setId(sessionId);
        session.setProblemId(problemId);
        session.setFileName(spec.fileName());
        session.setFileSizeBytes(spec.fileSizeBytes());
        session.setSha256(spec.sha256());
        session.setChunkSizeBytes(spec.chunkSizeBytes());
        session.setTotalChunks(spec.totalChunks());
        session.setUploadedChunks(uploadedChunks);
        session.setStatus(status);
        session.setTempDir(status == TestcasePackageStatus.UPLOADING ? "tmp/" + sessionId : tempDir);
        session.setPackageId(packageId);
        session.setCreatedBy(SecuritySupport.currentUserId());
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(now.plus(24, ChronoUnit.HOURS));
        sessionMapper.insert(session);
        return session;
    }

    private TestcaseUploadInitResponse toInitResponse(TestcaseUploadSessionEntity session, String message) {
        List<Integer> uploadedChunks = session.getStatus() == TestcasePackageStatus.READY
                ? fullChunkList(session.getTotalChunks())
                : uploadedChunkIndexes(session.getId());
        return new TestcaseUploadInitResponse(session.getId(), session.getStatus(), session.getPackageId(),
                uploadedChunks, session.getChunkSizeBytes(), session.getTotalChunks(), session.getExpiresAt(), message);
    }

    private void validateChunkSize(TestcaseUploadSessionEntity session, int index, long actualSize) {
        long expectedSize = expectedChunkSize(session, index);
        if (actualSize != expectedSize) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Chunk size does not match expected chunk range");
        }
    }

    private long expectedChunkSize(TestcaseUploadSessionEntity session, int index) {
        if (index < session.getTotalChunks() - 1) {
            return session.getChunkSizeBytes();
        }
        return session.getFileSizeBytes() - (long) session.getChunkSizeBytes() * (session.getTotalChunks() - 1);
    }

    private void verifyCompleteChunks(TestcaseUploadSessionEntity session, List<TestcaseUploadChunkEntity> chunks) {
        if (chunks.size() != session.getTotalChunks()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Not all testcase chunks have been uploaded");
        }
        Set<Integer> indexes = new HashSet<>();
        long uploadedBytes = 0L;
        for (TestcaseUploadChunkEntity chunk : chunks) {
            indexes.add(chunk.getChunkIndex());
            uploadedBytes += chunk.getChunkSizeBytes();
            if (!storageService.exists(chunk.getStoragePath())) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Uploaded testcase chunk is missing on disk: " + chunk.getChunkIndex());
            }
        }
        for (int i = 0; i < session.getTotalChunks(); i++) {
            if (!indexes.contains(i)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Missing testcase upload chunk: " + i);
            }
        }
        if (uploadedBytes != session.getFileSizeBytes()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Uploaded testcase chunk sizes do not match file size");
        }
    }

    private void markProcessing(TestcaseUploadSessionEntity session) {
        Instant now = Instant.now();
        sessionMapper.update(new TestcaseUploadSessionEntity(), new LambdaUpdateWrapper<TestcaseUploadSessionEntity>()
                .eq(TestcaseUploadSessionEntity::getId, session.getId())
                .set(TestcaseUploadSessionEntity::getStatus, TestcasePackageStatus.PROCESSING)
                .set(TestcaseUploadSessionEntity::getUpdatedAt, now)
                .set(TestcaseUploadSessionEntity::getErrorMessage, null));
        packageMapper.update(new TestcasePackageEntity(), new LambdaUpdateWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getId, session.getPackageId())
                .set(TestcasePackageEntity::getStatus, TestcasePackageStatus.PROCESSING)
                .set(TestcasePackageEntity::getUpdatedAt, now)
                .set(TestcasePackageEntity::getErrorMessage, null));
        session.setStatus(TestcasePackageStatus.PROCESSING);
    }

    private void saveReadyPackage(TestcaseUploadSessionEntity session, TestcaseStorageService.MergeResult merged,
                                  TestcasePackageValidator.ValidatedPackage validated) {
        saveValidatedPackage(session.getPackageId(), session.getProblemId(), merged, validated);
        Instant now = Instant.now();
        sessionMapper.update(new TestcaseUploadSessionEntity(), new LambdaUpdateWrapper<TestcaseUploadSessionEntity>()
                .eq(TestcaseUploadSessionEntity::getId, session.getId())
                .set(TestcaseUploadSessionEntity::getStatus, TestcasePackageStatus.READY)
                .set(TestcaseUploadSessionEntity::getUploadedChunks, session.getTotalChunks())
                .set(TestcaseUploadSessionEntity::getPackageId, session.getPackageId())
                .set(TestcaseUploadSessionEntity::getUpdatedAt, now)
                .set(TestcaseUploadSessionEntity::getErrorMessage, null));
    }

    private void saveValidatedPackage(Long packageId, Long problemId, TestcaseStorageService.MergeResult merged,
                                      TestcasePackageValidator.ValidatedPackage validated) {
        Instant now = Instant.now();
        caseMapper.delete(new LambdaQueryWrapper<TestcasePackageCaseEntity>()
                .eq(TestcasePackageCaseEntity::getPackageId, packageId));
        subtaskMapper.delete(new LambdaQueryWrapper<ProblemSubtaskEntity>()
                .eq(ProblemSubtaskEntity::getTestcasePackageId, packageId));
        int sampleCount = 0;
        for (TestcasePackageValidator.ValidatedSubtask validatedSubtask : validated.subtasks()) {
            ProblemSubtaskEntity subtask = new ProblemSubtaskEntity();
            subtask.setProblemId(problemId);
            subtask.setTestcasePackageId(packageId);
            subtask.setSubtaskKey(validatedSubtask.key());
            subtask.setTitle(validatedSubtask.title());
            subtask.setScore(validatedSubtask.score());
            subtask.setSortOrder(validatedSubtask.sortOrder());
            subtask.setCreatedAt(now);
            subtaskMapper.insert(subtask);
        }
        for (TestcasePackageValidator.ValidatedCase validatedCase : validated.cases()) {
            TestcasePackageCaseEntity entity = new TestcasePackageCaseEntity();
            entity.setPackageId(packageId);
            entity.setName(validatedCase.name());
            entity.setInputPath(validatedCase.inputPath());
            entity.setOutputPath(validatedCase.outputPath());
            entity.setSample(validatedCase.sample());
            entity.setSubtaskKey(validatedCase.subtaskKey());
            entity.setScore(validatedCase.score());
            entity.setInputSizeBytes(validatedCase.inputSizeBytes());
            entity.setOutputSizeBytes(validatedCase.outputSizeBytes());
            entity.setSortOrder(validatedCase.sortOrder());
            entity.setCreatedAt(now);
            caseMapper.insert(entity);
            if (validatedCase.sample()) {
                sampleCount++;
            }
        }
        packageMapper.update(new TestcasePackageEntity(), new LambdaUpdateWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getId, packageId)
                .set(TestcasePackageEntity::getVersion, validated.version())
                .set(TestcasePackageEntity::getFileSizeBytes, merged.sizeBytes())
                .set(TestcasePackageEntity::getSha256, merged.sha256())
                .set(TestcasePackageEntity::getStatus, TestcasePackageStatus.READY)
                .set(TestcasePackageEntity::getStorageProvider, TestcaseStorageService.LOCAL_PROVIDER)
                .set(TestcasePackageEntity::getStorageKey, merged.storageKey())
                .set(TestcasePackageEntity::getCaseCount, validated.cases().size())
                .set(TestcasePackageEntity::getSampleCount, sampleCount)
                .set(TestcasePackageEntity::getManifestJson, validated.manifestJson())
                .set(TestcasePackageEntity::getCheckerType, validated.checker().type())
                .set(TestcasePackageEntity::getCheckerLanguage, validated.checker().language())
                .set(TestcasePackageEntity::getCheckerSourcePath, validated.checker().source())
                .set(TestcasePackageEntity::getCheckerProtocol, validated.checker().protocol())
                .set(TestcasePackageEntity::getUpdatedAt, now)
                .set(TestcasePackageEntity::getErrorMessage, null));
    }

    private void markFailed(TestcaseUploadSessionEntity session, String message) {
        String error = truncate(message);
        Instant now = Instant.now();
        sessionMapper.update(new TestcaseUploadSessionEntity(), new LambdaUpdateWrapper<TestcaseUploadSessionEntity>()
                .eq(TestcaseUploadSessionEntity::getId, session.getId())
                .set(TestcaseUploadSessionEntity::getStatus, TestcasePackageStatus.FAILED)
                .set(TestcaseUploadSessionEntity::getUpdatedAt, now)
                .set(TestcaseUploadSessionEntity::getErrorMessage, error));
        packageMapper.update(new TestcasePackageEntity(), new LambdaUpdateWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getId, session.getPackageId())
                .set(TestcasePackageEntity::getStatus, TestcasePackageStatus.FAILED)
                .set(TestcasePackageEntity::getActive, false)
                .set(TestcasePackageEntity::getUpdatedAt, now)
                .set(TestcasePackageEntity::getErrorMessage, error));
        session.setStatus(TestcasePackageStatus.FAILED);
        session.setUpdatedAt(now);
        session.setErrorMessage(error);
    }

    private void refreshUploadedChunkCount(TestcaseUploadSessionEntity session) {
        int count = (int) chunkMapper.selectCount(new LambdaQueryWrapper<TestcaseUploadChunkEntity>()
                .eq(TestcaseUploadChunkEntity::getUploadId, session.getId())).longValue();
        sessionMapper.update(new TestcaseUploadSessionEntity(), new LambdaUpdateWrapper<TestcaseUploadSessionEntity>()
                .eq(TestcaseUploadSessionEntity::getId, session.getId())
                .set(TestcaseUploadSessionEntity::getUploadedChunks, count)
                .set(TestcaseUploadSessionEntity::getUpdatedAt, Instant.now()));
        session.setUploadedChunks(count);
    }

    private TestcasePackageResponse toResponse(TestcasePackageEntity testcasePackage) {
        List<TestcasePackageSubtaskResponse> subtasks = packageSubtasks(testcasePackage.getId())
                .stream()
                .map(this::toSubtaskResponse)
                .toList();
        List<TestcasePackageCaseResponse> cases = packageCases(testcasePackage.getId())
                .stream()
                .map(this::toCaseResponse)
                .toList();
        return new TestcasePackageResponse(testcasePackage.getId(), testcasePackage.getProblemId(),
                testcasePackage.getVersion(), testcasePackage.getFileName(), testcasePackage.getFileSizeBytes(),
                testcasePackage.getSha256(), testcasePackage.getStatus(), Boolean.TRUE.equals(testcasePackage.getActive()),
                testcasePackage.getCaseCount(), testcasePackage.getSampleCount(), testcasePackage.getStorageProvider(),
                testcasePackage.getCreatedAt(), testcasePackage.getActivatedAt(), testcasePackage.getArchivedAt(),
                testcasePackage.getDeletedAt(), testcasePackage.getDeletedBy(), testcasePackage.getErrorMessage(),
                toCheckerResponse(testcasePackage), subtasks, cases);
    }

    private List<ProblemSubtaskEntity> packageSubtasks(Long packageId) {
        return subtaskMapper.selectList(new LambdaQueryWrapper<ProblemSubtaskEntity>()
                .eq(ProblemSubtaskEntity::getTestcasePackageId, packageId)
                .orderByAsc(ProblemSubtaskEntity::getSortOrder)
                .orderByAsc(ProblemSubtaskEntity::getId));
    }

    private List<TestcasePackageCaseEntity> packageCases(Long packageId) {
        return caseMapper.selectList(new LambdaQueryWrapper<TestcasePackageCaseEntity>()
                .eq(TestcasePackageCaseEntity::getPackageId, packageId)
                .orderByAsc(TestcasePackageCaseEntity::getSortOrder)
                .orderByAsc(TestcasePackageCaseEntity::getId));
    }

    private TestcasePackageCheckerResponse toCheckerResponse(TestcasePackageEntity testcasePackage) {
        TestcaseCheckerType checkerType = testcasePackage.getCheckerType() == null
                ? TestcaseCheckerType.STANDARD
                : testcasePackage.getCheckerType();
        return new TestcasePackageCheckerResponse(checkerType, testcasePackage.getCheckerLanguage(),
                testcasePackage.getCheckerSourcePath(), testcasePackage.getCheckerProtocol());
    }

    private TestcasePackageSubtaskResponse toSubtaskResponse(ProblemSubtaskEntity entity) {
        return new TestcasePackageSubtaskResponse(entity.getId(), entity.getSubtaskKey(), entity.getTitle(),
                entity.getScore(), entity.getSortOrder());
    }

    private TestcasePackageCaseResponse toCaseResponse(TestcasePackageCaseEntity entity) {
        return new TestcasePackageCaseResponse(entity.getId(), entity.getName(), entity.getInputPath(),
                entity.getOutputPath(), Boolean.TRUE.equals(entity.getSample()), entity.getSubtaskKey(), entity.getScore(),
                entity.getInputSizeBytes(), entity.getOutputSizeBytes(), entity.getSortOrder());
    }

    private List<Integer> uploadedChunkIndexes(String uploadId) {
        return chunks(uploadId).stream().map(TestcaseUploadChunkEntity::getChunkIndex).toList();
    }

    private List<Integer> fullChunkList(int totalChunks) {
        return IntStream.range(0, totalChunks).boxed().toList();
    }

    private List<TestcaseUploadChunkEntity> chunks(String uploadId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<TestcaseUploadChunkEntity>()
                .eq(TestcaseUploadChunkEntity::getUploadId, uploadId)
                .orderByAsc(TestcaseUploadChunkEntity::getChunkIndex));
    }

    private TestcaseUploadChunkEntity findChunk(String uploadId, int index) {
        return chunkMapper.selectOne(new LambdaQueryWrapper<TestcaseUploadChunkEntity>()
                .eq(TestcaseUploadChunkEntity::getUploadId, uploadId)
                .eq(TestcaseUploadChunkEntity::getChunkIndex, index));
    }

    private TestcaseUploadSessionEntity latestSession(Long problemId, String sha256, Long packageId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<TestcaseUploadSessionEntity>()
                .eq(TestcaseUploadSessionEntity::getProblemId, problemId)
                .eq(TestcaseUploadSessionEntity::getSha256, sha256)
                .eq(TestcaseUploadSessionEntity::getPackageId, packageId)
                .orderByDesc(TestcaseUploadSessionEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    private TestcasePackageEntity findPackageBySha(Long problemId, String sha256) {
        return packageMapper.selectOne(new LambdaQueryWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getProblemId, problemId)
                .eq(TestcasePackageEntity::getSha256, sha256));
    }

    private TestcasePackageEntity requirePackage(Long packageId) {
        TestcasePackageEntity testcasePackage = packageMapper.selectById(packageId);
        if (testcasePackage == null || testcasePackage.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Testcase package not found");
        }
        return testcasePackage;
    }

    private TestcasePackageEntity requirePackage(Long problemId, Long packageId) {
        TestcasePackageEntity testcasePackage = packageMapper.selectOne(new LambdaQueryWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getId, packageId)
                .eq(TestcasePackageEntity::getProblemId, problemId)
                .isNull(TestcasePackageEntity::getDeletedAt));
        if (testcasePackage == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Testcase package not found");
        }
        return testcasePackage;
    }

    private TestcaseUploadSessionEntity requireUploadSession(Long problemId, String uploadId) {
        TestcaseUploadSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<TestcaseUploadSessionEntity>()
                .eq(TestcaseUploadSessionEntity::getId, uploadId)
                .eq(TestcaseUploadSessionEntity::getProblemId, problemId));
        if (session == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Testcase upload session not found");
        }
        return session;
    }

    private void requireProblem(Long problemId) {
        if (!problemCatalog.existsActive(problemId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem not found");
        }
    }

    private void assertCanArchiveOrDelete(TestcasePackageEntity testcasePackage) {
        if (Boolean.TRUE.equals(testcasePackage.getActive())) {
            throw new DomainException(ErrorCode.CONFLICT, "Active testcase package cannot be archived or deleted");
        }
        if (testcasePackage.getStatus() == TestcasePackageStatus.UPLOADING
                || testcasePackage.getStatus() == TestcasePackageStatus.PROCESSING) {
            throw new DomainException(ErrorCode.CONFLICT, "Processing testcase package cannot be archived or deleted");
        }
    }

    private void assertUploading(TestcaseUploadSessionEntity session) {
        if (session.getStatus() == TestcasePackageStatus.FAILED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase upload has failed: " + session.getErrorMessage());
        }
        if (session.getStatus() != TestcasePackageStatus.UPLOADING) {
            throw new DomainException(ErrorCode.CONFLICT, "Testcase upload is not accepting chunks");
        }
        if (session.getExpiresAt() != null && Instant.now().isAfter(session.getExpiresAt())) {
            markFailed(session, "Testcase upload session expired");
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase upload session expired");
        }
    }

    private String userMessage(Throwable ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "Failed to process testcase package";
    }

    private String failMessage(TestcaseUploadFailRequest request) {
        if (request != null && StringUtils.hasText(request.message())) {
            return request.message().trim();
        }
        return "Testcase upload was interrupted. Please upload the package again.";
    }

    private String truncate(String value) {
        if (value == null || value.length() <= ERROR_LIMIT) {
            return value;
        }
        return value.substring(0, ERROR_LIMIT);
    }

    private record InitSpec(String fileName, long fileSizeBytes, String sha256, int chunkSizeBytes, int totalChunks) {
    }

    public record AppendCasesMetadata(List<AppendCaseMetadata> cases) {
    }

    public record AppendCaseMetadata(String caseName, Integer score, String subtaskKey) {
    }

    private record PendingAppendCase(String caseName, int score, String subtaskKey,
                                     MultipartFile inputFile, MultipartFile outputFile) {
    }

    private record AppendCaseSpec(String caseName, int score, String subtaskKey, String inputPath, String outputPath,
                                  MultipartFile inputFile, MultipartFile outputFile) {
    }
}
