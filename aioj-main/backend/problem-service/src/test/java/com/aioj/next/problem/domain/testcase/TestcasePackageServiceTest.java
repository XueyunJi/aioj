package com.aioj.next.problem.domain.testcase;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.problem.TestcasePackageStatus;
import com.aioj.next.contract.problem.TestcaseUploadFailRequest;
import com.aioj.next.problem.config.TestcaseProperties;
import com.aioj.next.problem.domain.OperationAuditService;
import com.aioj.next.problem.domain.ProblemCatalog;
import com.aioj.next.problem.persistence.entity.ProblemSubtaskEntity;
import com.aioj.next.problem.persistence.entity.TestcasePackageCaseEntity;
import com.aioj.next.problem.persistence.entity.TestcasePackageEntity;
import com.aioj.next.problem.persistence.entity.TestcaseUploadSessionEntity;
import com.aioj.next.problem.persistence.mapper.ProblemSubtaskMapper;
import com.aioj.next.problem.persistence.mapper.TestcasePackageCaseMapper;
import com.aioj.next.problem.persistence.mapper.TestcasePackageMapper;
import com.aioj.next.problem.persistence.mapper.TestcaseUploadChunkMapper;
import com.aioj.next.problem.persistence.mapper.TestcaseUploadSessionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestcasePackageServiceTest {
    @Mock
    private ProblemCatalog problemCatalog;
    @Mock
    private TestcaseProperties properties;
    @Mock
    private TestcaseStorageService storageService;
    @Mock
    private TestcasePackageValidator validator;
    @Mock
    private TestcasePackageMapper packageMapper;
    @Mock
    private TestcasePackageCaseMapper caseMapper;
    @Mock
    private ProblemSubtaskMapper subtaskMapper;
    @Mock
    private TestcaseUploadSessionMapper sessionMapper;
    @Mock
    private TestcaseUploadChunkMapper chunkMapper;
    @Mock
    private OperationAuditService auditService;

    private TestcasePackageService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TestcasePackageEntity.class);
        TableInfoHelper.initTableInfo(assistant, TestcaseUploadSessionEntity.class);
        service = new TestcasePackageService(problemCatalog, properties, storageService, validator, packageMapper,
                caseMapper, subtaskMapper, sessionMapper, chunkMapper, auditService, new ObjectMapper());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(99L, "teacher", Set.of(Role.TEACHER)), "n/a"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void findReadyOrThrowAllowsArchivedReadyPackageForHistoricalUse() {
        TestcasePackageEntity archivedReadyPackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, "packages/a.zip");
        archivedReadyPackage.setArchivedAt(Instant.parse("2026-06-01T00:00:00Z"));
        when(packageMapper.selectById(1L)).thenReturn(archivedReadyPackage);

        assertSame(archivedReadyPackage, service.findReadyOrThrow(1L));
    }

    @Test
    void findDownloadableAllowsArchivedReadyPackage() {
        TestcasePackageEntity archivedReadyPackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, "packages/a.zip");
        archivedReadyPackage.setArchivedAt(Instant.parse("2026-06-01T00:00:00Z"));
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(archivedReadyPackage);

        assertSame(archivedReadyPackage, service.findDownloadable(10L, 1L));
    }

    @Test
    void findDownloadableRejectsFailedPackage() {
        TestcasePackageEntity failedPackage = packageEntity(1L, 10L, TestcasePackageStatus.FAILED, "packages/a.zip");
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(failedPackage);

        DomainException exception = assertThrows(DomainException.class, () -> service.findDownloadable(10L, 1L));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
    }

    @Test
    void findDownloadableRejectsMissingStorageKey() {
        TestcasePackageEntity readyPackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, null);
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(readyPackage);

        DomainException exception = assertThrows(DomainException.class, () -> service.findDownloadable(10L, 1L));

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode());
    }

    @Test
    void findDownloadableRejectsCrossProblemPackage() {
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        DomainException exception = assertThrows(DomainException.class, () -> service.findDownloadable(10L, 1L));

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode());
    }

    @Test
    void failUploadMarksUploadingSessionAndPackageFailed() {
        TestcaseUploadSessionEntity session = sessionEntity("upload-1", 10L, 1L, TestcasePackageStatus.UPLOADING);
        when(sessionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(session);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = service.failUpload(10L, "upload-1", new TestcaseUploadFailRequest("client failed"));

        assertEquals(TestcasePackageStatus.FAILED, response.status());
        assertEquals("client failed", response.errorMessage());
        verify(sessionMapper).update(any(TestcaseUploadSessionEntity.class), any());
        verify(packageMapper).update(any(TestcasePackageEntity.class), any());
        verify(storageService).deleteTempUpload("upload-1");
    }

    @Test
    void failUploadIsIdempotentForFailedSession() {
        TestcaseUploadSessionEntity session = sessionEntity("upload-1", 10L, 1L, TestcasePackageStatus.FAILED);
        session.setErrorMessage("already failed");
        when(sessionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(session);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = service.failUpload(10L, "upload-1", new TestcaseUploadFailRequest("client failed"));

        assertEquals(TestcasePackageStatus.FAILED, response.status());
        assertEquals("already failed", response.errorMessage());
        verify(sessionMapper, never()).update(any(TestcaseUploadSessionEntity.class), any());
        verify(packageMapper, never()).update(any(TestcasePackageEntity.class), any());
        verify(storageService, never()).deleteTempUpload(any());
    }

    @Test
    void failUploadDoesNotOverrideProcessingOrReadySession() {
        for (TestcasePackageStatus status : List.of(TestcasePackageStatus.PROCESSING, TestcasePackageStatus.READY)) {
            TestcaseUploadSessionEntity session = sessionEntity("upload-" + status, 10L, 1L, status);
            when(sessionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(session);
            if (status == TestcasePackageStatus.PROCESSING) {
                when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            }

            var response = service.failUpload(10L, session.getId(), new TestcaseUploadFailRequest("client failed"));

            assertEquals(status, response.status());
            verify(sessionMapper, never()).update(any(TestcaseUploadSessionEntity.class), any());
            verify(packageMapper, never()).update(any(TestcasePackageEntity.class), any());
            verify(storageService, never()).deleteTempUpload(any());
        }
    }

    @Test
    void restoreClearsArchivedAtWithExplicitNullUpdate() {
        TestcasePackageEntity archivedPackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, "packages/a.zip");
        archivedPackage.setArchivedAt(Instant.parse("2026-06-01T00:00:00Z"));
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(archivedPackage);
        when(subtaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(caseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = service.restore(10L, 1L);

        assertNull(response.archivedAt());
        verify(packageMapper).update(any(TestcasePackageEntity.class), any(UpdateWrapper.class));
    }

    @Test
    void appendCaseCreatesNewReadyPackageAndActivatesWhenSourceIsActive(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TestcaseProperties testcaseProperties = testcaseProperties(tempDir);
        LocalTestcaseStorageService localStorage = new LocalTestcaseStorageService(testcaseProperties);
        TestcasePackageValidator realValidator = new TestcasePackageValidator(testcaseProperties, objectMapper);
        TestcasePackageService appendService = new TestcasePackageService(problemCatalog, testcaseProperties,
                localStorage, realValidator, packageMapper, caseMapper, subtaskMapper, sessionMapper,
                chunkMapper, auditService, objectMapper);

        String sourceSha = "a".repeat(64);
        String sourceStorageKey = localStorage.packageStorageKey(10L, sourceSha);
        Path sourceZip = localStorage.resolveStorageKey(sourceStorageKey);
        writeBasePackage(sourceZip, objectMapper);
        TestcasePackageEntity sourcePackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, sourceStorageKey);
        sourcePackage.setVersion("v1");
        sourcePackage.setActive(true);
        sourcePackage.setSha256(sourceSha);
        sourcePackage.setFileSizeBytes(Files.size(sourceZip));
        sourcePackage.setCaseCount(1);
        sourcePackage.setSampleCount(0);

        TestcasePackageCaseEntity sourceCase = testcaseCase(1L, "1", "1.in", "1.out", 1);
        AtomicReference<TestcasePackageEntity> inserted = new AtomicReference<>();
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sourcePackage);
        when(caseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sourceCase), List.of(sourceCase), List.of());
        when(subtaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(), List.of(), List.of());
        when(packageMapper.insert(any(TestcasePackageEntity.class))).thenAnswer(invocation -> {
            TestcasePackageEntity entity = invocation.getArgument(0);
            entity.setId(2L);
            inserted.set(entity);
            return 1;
        });
        when(packageMapper.selectById(2L)).thenAnswer(invocation -> {
            TestcasePackageEntity entity = inserted.get();
            entity.setStatus(TestcasePackageStatus.READY);
            entity.setVersion("v1-plus-test");
            entity.setCaseCount(2);
            entity.setSampleCount(0);
            return entity;
        });

        appendService.appendCase(10L, 1L,
                new MockMultipartFile("inputFile", "2.in", "text/plain", "2\n".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("outputFile", "2.out", "text/plain", "4\n".getBytes(StandardCharsets.UTF_8)),
                "case-2", 1, null);

        TestcasePackageEntity generated = inserted.get();
        assertNotNull(generated);
        Path generatedZip = localStorage.resolveStorageKey(generated.getStorageKey());
        assertTrue(Files.isRegularFile(generatedZip));
        try (ZipFile zipFile = new ZipFile(generatedZip.toFile())) {
            assertNotNull(zipFile.getEntry("1.in"));
            assertNotNull(zipFile.getEntry("1.out"));
            assertTrue(zipFile.stream().anyMatch(entry -> entry.getName().startsWith("manual/case-2") && entry.getName().endsWith(".in")));
            assertTrue(zipFile.stream().anyMatch(entry -> entry.getName().startsWith("manual/case-2") && entry.getName().endsWith(".out")));
            String manifest = new String(zipFile.getInputStream(zipFile.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"case-2\""));
            assertTrue(manifest.contains("\"v1-plus-"));
        }
        verify(packageMapper).insert(any(TestcasePackageEntity.class));
        verify(packageMapper, atLeastOnce()).update(any(TestcasePackageEntity.class), any());
        verify(auditService).recordCurrentUser(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void appendCasesCreatesOneReadyPackageWithMultipleNewCases(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TestcaseProperties testcaseProperties = testcaseProperties(tempDir);
        LocalTestcaseStorageService localStorage = new LocalTestcaseStorageService(testcaseProperties);
        TestcasePackageValidator realValidator = new TestcasePackageValidator(testcaseProperties, objectMapper);
        TestcasePackageService appendService = new TestcasePackageService(problemCatalog, testcaseProperties,
                localStorage, realValidator, packageMapper, caseMapper, subtaskMapper, sessionMapper,
                chunkMapper, auditService, objectMapper);

        String sourceSha = "b".repeat(64);
        String sourceStorageKey = localStorage.packageStorageKey(10L, sourceSha);
        Path sourceZip = localStorage.resolveStorageKey(sourceStorageKey);
        writeBasePackage(sourceZip, objectMapper);
        TestcasePackageEntity sourcePackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, sourceStorageKey);
        sourcePackage.setVersion("v1");
        sourcePackage.setActive(true);
        sourcePackage.setSha256(sourceSha);
        sourcePackage.setFileSizeBytes(Files.size(sourceZip));
        sourcePackage.setCaseCount(1);
        sourcePackage.setSampleCount(0);

        TestcasePackageCaseEntity sourceCase = testcaseCase(1L, "1", "1.in", "1.out", 1);
        AtomicReference<TestcasePackageEntity> inserted = new AtomicReference<>();
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sourcePackage);
        when(caseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sourceCase), List.of(sourceCase), List.of());
        when(subtaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(), List.of(), List.of());
        when(packageMapper.insert(any(TestcasePackageEntity.class))).thenAnswer(invocation -> {
            TestcasePackageEntity entity = invocation.getArgument(0);
            entity.setId(2L);
            inserted.set(entity);
            return 1;
        });
        when(packageMapper.selectById(2L)).thenAnswer(invocation -> {
            TestcasePackageEntity entity = inserted.get();
            entity.setStatus(TestcasePackageStatus.READY);
            entity.setVersion("v1-plus-test");
            entity.setCaseCount(3);
            entity.setSampleCount(0);
            return entity;
        });

        String metadata = objectMapper.writeValueAsString(Map.of("cases", List.of(
                Map.of("caseName", "case-2", "score", 1),
                Map.of("caseName", "case-3", "score", 2))));

        appendService.appendCases(10L, 1L, metadata,
                List.of(
                        new MockMultipartFile("inputFiles", "2.in", "text/plain", "2\n".getBytes(StandardCharsets.UTF_8)),
                        new MockMultipartFile("inputFiles", "3.in", "text/plain", "3\n".getBytes(StandardCharsets.UTF_8))),
                List.of(
                        new MockMultipartFile("outputFiles", "2.out", "text/plain", "4\n".getBytes(StandardCharsets.UTF_8)),
                        new MockMultipartFile("outputFiles", "3.out", "text/plain", "9\n".getBytes(StandardCharsets.UTF_8))));

        TestcasePackageEntity generated = inserted.get();
        assertNotNull(generated);
        Path generatedZip = localStorage.resolveStorageKey(generated.getStorageKey());
        assertTrue(Files.isRegularFile(generatedZip));
        try (ZipFile zipFile = new ZipFile(generatedZip.toFile())) {
            assertTrue(zipFile.stream().anyMatch(entry -> entry.getName().startsWith("manual/case-2") && entry.getName().endsWith(".in")));
            assertTrue(zipFile.stream().anyMatch(entry -> entry.getName().startsWith("manual/case-2") && entry.getName().endsWith(".out")));
            assertTrue(zipFile.stream().anyMatch(entry -> entry.getName().startsWith("manual/case-3") && entry.getName().endsWith(".in")));
            assertTrue(zipFile.stream().anyMatch(entry -> entry.getName().startsWith("manual/case-3") && entry.getName().endsWith(".out")));
            String manifest = new String(zipFile.getInputStream(zipFile.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"case-2\""));
            assertTrue(manifest.contains("\"case-3\""));
        }
        verify(packageMapper).insert(any(TestcasePackageEntity.class));
        verify(auditService).recordCurrentUser(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void appendCaseRejectsArchivedPackage() {
        TestcasePackageEntity archivedPackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, "packages/a.zip");
        archivedPackage.setArchivedAt(Instant.parse("2026-06-01T00:00:00Z"));
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(archivedPackage);

        TestcasePackageService appendService = new TestcasePackageService(problemCatalog, testcaseProperties(Path.of(".")),
                storageService, validator, packageMapper, caseMapper, subtaskMapper, sessionMapper,
                chunkMapper, auditService, new ObjectMapper());

        DomainException exception = assertThrows(DomainException.class, () -> appendService.appendCase(10L, 1L,
                new MockMultipartFile("inputFile", "2.in", "text/plain", "2\n".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("outputFile", "2.out", "text/plain", "4\n".getBytes(StandardCharsets.UTF_8)),
                "case-2", 1, null));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
    }

    @Test
    void appendCaseRejectsInvalidFileExtension() {
        TestcasePackageEntity readyPackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, "packages/a.zip");
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(readyPackage);

        TestcasePackageService appendService = new TestcasePackageService(problemCatalog, testcaseProperties(Path.of(".")),
                storageService, validator, packageMapper, caseMapper, subtaskMapper, sessionMapper,
                chunkMapper, auditService, new ObjectMapper());

        DomainException exception = assertThrows(DomainException.class, () -> appendService.appendCase(10L, 1L,
                new MockMultipartFile("inputFile", "2.txt", "text/plain", "2\n".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("outputFile", "2.out", "text/plain", "4\n".getBytes(StandardCharsets.UTF_8)),
                "case-2", 1, null));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
    }

    @Test
    void appendCaseRejectsSingleFileOverCaseLimit() {
        TestcasePackageEntity readyPackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, "packages/a.zip");
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(readyPackage);
        when(subtaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(caseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        TestcasePackageService appendService = new TestcasePackageService(problemCatalog, testcaseProperties(Path.of(".")),
                storageService, validator, packageMapper, caseMapper, subtaskMapper, sessionMapper,
                chunkMapper, auditService, new ObjectMapper());

        DomainException exception = assertThrows(DomainException.class, () -> appendService.appendCase(10L, 1L,
                new MockMultipartFile("inputFile", "2.in", "text/plain", new byte[8 * 1024 * 1024 + 1]),
                new MockMultipartFile("outputFile", "2.out", "text/plain", "4\n".getBytes(StandardCharsets.UTF_8)),
                "case-2", 1, null));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
        verify(packageMapper, never()).insert(any(TestcasePackageEntity.class));
    }

    @Test
    void appendCasesRejectsDuplicateCaseNames() throws Exception {
        TestcasePackageEntity readyPackage = packageEntity(1L, 10L, TestcasePackageStatus.READY, "packages/a.zip");
        when(problemCatalog.existsActive(10L)).thenReturn(true);
        when(packageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(readyPackage);
        when(subtaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(caseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        TestcasePackageService appendService = new TestcasePackageService(problemCatalog, testcaseProperties(Path.of(".")),
                storageService, validator, packageMapper, caseMapper, subtaskMapper, sessionMapper,
                chunkMapper, auditService, new ObjectMapper());

        String metadata = new ObjectMapper().writeValueAsString(Map.of("cases", List.of(
                Map.of("caseName", "case-2", "score", 1),
                Map.of("caseName", "CASE-2", "score", 1))));

        DomainException exception = assertThrows(DomainException.class, () -> appendService.appendCases(10L, 1L, metadata,
                List.of(
                        new MockMultipartFile("inputFiles", "2.in", "text/plain", "2\n".getBytes(StandardCharsets.UTF_8)),
                        new MockMultipartFile("inputFiles", "3.in", "text/plain", "3\n".getBytes(StandardCharsets.UTF_8))),
                List.of(
                        new MockMultipartFile("outputFiles", "2.out", "text/plain", "4\n".getBytes(StandardCharsets.UTF_8)),
                        new MockMultipartFile("outputFiles", "3.out", "text/plain", "9\n".getBytes(StandardCharsets.UTF_8)))));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
        verify(packageMapper, never()).insert(any(TestcasePackageEntity.class));
    }

    private static TestcaseUploadSessionEntity sessionEntity(String id, Long problemId, Long packageId,
                                                            TestcasePackageStatus status) {
        TestcaseUploadSessionEntity entity = new TestcaseUploadSessionEntity();
        entity.setId(id);
        entity.setProblemId(problemId);
        entity.setPackageId(packageId);
        entity.setStatus(status);
        entity.setTotalChunks(1);
        entity.setUploadedChunks(status == TestcasePackageStatus.READY ? 1 : 0);
        entity.setFileName("tests.zip");
        entity.setFileSizeBytes(1_300_000L);
        entity.setSha256("0".repeat(64));
        entity.setChunkSizeBytes(4 * 1024 * 1024);
        entity.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        entity.setExpiresAt(Instant.parse("2026-06-02T00:00:00Z"));
        return entity;
    }

    private static TestcaseProperties testcaseProperties(Path storageRoot) {
        TestcaseProperties properties = new TestcaseProperties();
        properties.setStorageRoot(storageRoot.toAbsolutePath().toString());
        properties.setMaxPackageBytes(20 * 1024 * 1024L);
        properties.setMaxCaseFileBytes(8 * 1024 * 1024L);
        properties.setMaxUncompressedBytes(20 * 1024 * 1024L);
        properties.setMaxEntryCount(100);
        return properties;
    }

    private static void writeBasePackage(Path zipPath, ObjectMapper objectMapper) throws Exception {
        Files.createDirectories(zipPath.getParent());
        TestcasePackageValidator.ManifestPayload manifest = new TestcasePackageValidator.ManifestPayload(
                "v1",
                null,
                List.of(),
                List.of(new TestcasePackageValidator.ManifestCase("1", "1.in", "1.out", false, null, 1)));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            output.putNextEntry(new ZipEntry("1.in"));
            output.write("1\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("1.out"));
            output.write("1\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("manifest.json"));
            output.write(objectMapper.writeValueAsBytes(manifest));
            output.closeEntry();
        }
    }

    private static TestcasePackageCaseEntity testcaseCase(Long packageId, String name, String inputPath,
                                                          String outputPath, Integer score) {
        TestcasePackageCaseEntity entity = new TestcasePackageCaseEntity();
        entity.setPackageId(packageId);
        entity.setName(name);
        entity.setInputPath(inputPath);
        entity.setOutputPath(outputPath);
        entity.setSample(false);
        entity.setScore(score);
        entity.setSortOrder(0);
        entity.setInputSizeBytes(2L);
        entity.setOutputSizeBytes(2L);
        return entity;
    }

    private static TestcasePackageEntity packageEntity(Long id, Long problemId, TestcasePackageStatus status, String storageKey) {
        TestcasePackageEntity entity = new TestcasePackageEntity();
        entity.setId(id);
        entity.setProblemId(problemId);
        entity.setStatus(status);
        entity.setStorageKey(storageKey);
        entity.setFileName("tests.zip");
        entity.setActive(false);
        entity.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        return entity;
    }
}
