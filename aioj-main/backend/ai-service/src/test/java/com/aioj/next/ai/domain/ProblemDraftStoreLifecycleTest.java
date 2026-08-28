package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.problem.ComplexityReport;
import com.aioj.next.ai.domain.problem.CrossCheckMismatch;
import com.aioj.next.ai.domain.problem.CrossCheckReport;
import com.aioj.next.ai.domain.problem.DraftExecutionReport;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.domain.problem.ProblemDraftRepairer;
import com.aioj.next.ai.domain.problem.ProblemDraftStressGeneratorResult;
import com.aioj.next.ai.domain.problem.ProblemDraftStaticValidator;
import com.aioj.next.ai.domain.problem.VerificationError;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.persistence.entity.ProblemDraftEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.ProblemDraftApprovalRequest;
import com.aioj.next.contract.ai.ProblemDraftRefineRequest;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftStoreLifecycleTest {
    @Mock
    private AiProvider aiProvider;
    @Mock
    private AiQuotaService aiQuotaService;
    @Mock
    private ProblemDraftMapper problemDraftMapper;
    @Mock
    private ProblemServiceClient problemServiceClient;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private OperationAuditWriter auditWriter;
    @Mock
    private DraftSandboxClient draftSandboxClient;
    @Mock
    private ProblemDraftRepairer problemDraftRepairer;
    @Mock
    private ProblemDraftTestcaseArtifactService testcaseArtifactService;

    private ProblemDraftStore store;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        store = new ProblemDraftStore(aiProvider, aiQuotaService,
                new AiCapacityService(properties),
                problemDraftMapper, problemServiceClient,
                new ObjectMapper(), transactionTemplate, auditWriter,
                new ProblemDraftStaticValidator(properties), draftSandboxClient, problemDraftRepairer);
        store.setProblemDraftTestcaseArtifactService(testcaseArtifactService);
        lenient().when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()));
        lenient().when(problemDraftRepairer.maxRepairAttempts()).thenReturn(5);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void restoreClearsArchivedAtWithExplicitNullUpdate() {
        ProblemDraftEntity draft = draft(1L);
        draft.setArchivedAt(LocalDateTime.parse("2026-06-02T00:00:00"));
        when(problemDraftMapper.selectById(1L)).thenReturn(draft);

        var response = store.restore(1L);

        assertNull(response.archivedAt());
        verify(problemDraftMapper).update(any(ProblemDraftEntity.class), any(UpdateWrapper.class));
    }

    @Test
    void deleteAllowsArchivedImportedDraftWithoutTouchingImportedProblem() {
        authenticateAsAdmin();
        ProblemDraftEntity draft = draft(2L);
        draft.setArchivedAt(LocalDateTime.parse("2026-06-02T00:00:00"));
        draft.setImportedProblemId(200L);
        when(problemDraftMapper.selectById(2L)).thenReturn(draft);

        store.delete(2L);

        assertNotNull(draft.getDeletedAt());
        assertEquals(99L, draft.getDeletedBy());
        verify(problemDraftMapper).updateById(draft);
        verify(auditWriter).record(eq("SOFT_DELETE"), eq("PROBLEM_DRAFT"), eq(2L), eq("SUCCESS"), any());
        verifyNoInteractions(problemServiceClient);
    }

    @Test
    void deleteStillRequiresArchivedDraft() {
        ProblemDraftEntity draft = draft(3L);
        when(problemDraftMapper.selectById(3L)).thenReturn(draft);

        assertThrows(DomainException.class, () -> store.delete(3L));

        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void importRequiresApprovedDraft() {
        ProblemDraftEntity draft = draft(4L);
        when(problemDraftMapper.selectById(4L)).thenReturn(draft);

        assertThrows(DomainException.class,
                () -> store.approve(4L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void generateMarksDraftInvalidWhenProviderReturnsNull() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(null);

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("INVALID", response.validationStatus());
        assertTrue(response.validationErrors().contains("Provider returned no draft"));
        assertTrue(response.validationErrors().contains("statement is required"));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generatePropagatesPlanGateFailureWithoutPersistingDraft() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class)))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_FAILED,
                        "Problem design plan gate failed: requested algorithm tokens are missing"));

        assertThrows(DomainException.class, () -> store.generate(99L, request()));

        verify(problemDraftMapper, never()).insert(any(ProblemDraftEntity.class));
        verifyNoInteractions(problemServiceClient);
    }

    @Test
    void generateMarksDraftInvalidWhenRequiredFieldsAreMissing() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                10L,
                "",
                "",
                "",
                "",
                validCases()
        ));

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("INVALID", response.validationStatus());
        assertTrue(response.validationErrors().contains("title is required"));
        assertTrue(response.validationErrors().contains("statement is required"));
        assertTrue(response.validationErrors().contains("standardSolutionCode is required"));
        assertTrue(response.validationErrors().contains("testcaseGeneratorPython is required"));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generateMarksDraftInvalidWhenRequiredGeneratedBlocksAreMissing() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(new ProblemDraftResponse(
                10L,
                "PENDING_REVIEW",
                "Missing generated blocks",
                "EASY",
                "statement",
                "notes",
                "cpp",
                "",
                "",
                "plan",
                List.of("array"),
                "VALID",
                List.of(),
                List.of(new TestCaseDto("1\n", "1\n", true)),
                1000,
                262144,
                null,
                "mock",
                10,
                20,
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null
        ));

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("INVALID", response.validationStatus());
        assertTrue(response.validationErrors().contains("standardSolutionCode is required"));
        assertTrue(response.validationErrors().contains("testcaseGeneratorPython is required"));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generateMarksDraftInvalidWhenStatementContainsSampleSections() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(new ProblemDraftResponse(
                11L,
                "PENDING_REVIEW",
                "Statement contains samples",
                "EASY",
                """
                        题目描述 给定一个数组。
                        输入描述 第一行一个整数 n。
                        输出描述 输出答案。
                        样例输入
                        3
                        样例输出
                        6
                        """,
                "样例说明：答案为 6。",
                "cpp",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                "生成计划",
                List.of("array"),
                "VALID",
                List.of(),
                List.of(new TestCaseDto("3\n", "6\n", true)),
                1000,
                262144,
                null,
                "mock",
                10,
                20,
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null
        ));

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("INVALID", response.validationStatus());
        assertTrue(response.validationErrors().contains("statement must only contain problem description, input description, and output description"));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generateMarksDraftInvalidWhenSampleCaseCountIsBelowMinimum() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                12L,
                "Too few samples",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                List.of(
                        new TestCaseDto("1 2\n", "3\n", true),
                        new TestCaseDto("2 3\n", "5\n", true)
                )
        ));

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("INVALID", response.validationStatus());
        assertTrue(response.validationErrors().contains("testCases must include 3 to 5 sample cases"));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generateMarksDraftInvalidWhenSampleCasesUsePlaceholderText() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                13L,
                "Placeholder samples",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                List.of(
                        new TestCaseDto("由脚本生成\n", "3\n", true),
                        new TestCaseDto("2 3\n", "omitted\n", true),
                        new TestCaseDto("10 20\n", "30\n", true)
                )
        ));

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("INVALID", response.validationStatus());
        assertTrue(response.validationErrors().contains("testCases[0].input must be concrete sample data"));
        assertTrue(response.validationErrors().contains("testCases[1].expectedOutput must be concrete sample data"));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generateStoresVerificationReportWhenDraftPassesSandbox() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                15L,
                "Verified draft",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("VALID", response.validationStatus());
        assertEquals("EXECUTION_VERIFIED", response.verificationStatus());
        assertNotNull(response.verificationReportJson());
        assertTrue(response.verificationReportJson().contains("\"sandboxReport\""));
        ArgumentCaptor<ProblemDraftEntity> captor = ArgumentCaptor.forClass(ProblemDraftEntity.class);
        verify(problemDraftMapper).insert(captor.capture());
        assertEquals("EXECUTION_VERIFIED", captor.getValue().getVerificationStatus());
        assertNotNull(captor.getValue().getVerificationReportJson());
    }

    @Test
    void generateDoesNotRepairWhenAutoRepairIsDisabled() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                16L,
                "Needs repair but disabled",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any())).thenReturn(failedSandboxReport());

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("FAILED", response.verificationStatus());
        assertEquals(0, response.repairAttemptCount());
        verify(problemDraftRepairer, never()).repair(any(), any(), any(), anyInt(), anyInt());
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generateRepairsDraftWhenAutoRepairIsEnabled() {
        ProblemDraftRequest request = requestWithAutoRepair();
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                17L,
                "Repair succeeds",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(failedSandboxReport())
                .thenReturn(new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()));
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), eq(request), any(), eq(1), eq(5)))
                .thenAnswer(invocation -> repairedDraft(invocation.getArgument(0), "修正样例输出", 1));

        ProblemDraftResponse response = store.generate(99L, request);

        assertEquals("EXECUTION_VERIFIED", response.verificationStatus());
        assertEquals(1, response.repairAttemptCount());
        assertEquals("修正样例输出", response.lastRepairReason());
        verify(problemDraftRepairer, times(1)).repair(any(ProblemDraftResponse.class), eq(request), any(), eq(1), eq(5));
        ArgumentCaptor<ProblemDraftEntity> captor = ArgumentCaptor.forClass(ProblemDraftEntity.class);
        verify(problemDraftMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getRepairAttemptCount());
        assertEquals("修正样例输出", captor.getValue().getLastRepairReason());
    }

    @Test
    void generateRepairsDraftWhenReferenceCheckFails() {
        ProblemDraftRequest request = requestWithAutoRepairAndReferenceCheck();
        ProblemDraftResponse generated = withReferenceSolver(validGeneratedDraft(
                22L,
                "Reference mismatch",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(generated);
        when(aiProvider.generateProblemDraftStressGenerator(any(Long.class), eq(request), any(ProblemDraftResponse.class)))
                .thenReturn(new ProblemDraftStressGeneratorResult("from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)", "stress-model", 2, 3));
        when(draftSandboxClient.verifyDraftDetailed(any(ProblemDraftResponse.class), any()))
                .thenReturn(referenceMismatchExecutionReport())
                .thenReturn(new DraftExecutionReport(new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()),
                        CrossCheckReport.passed(2)));
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), eq(request), any(), eq(1), eq(5)))
                .thenAnswer(invocation -> repairedDraft(invocation.getArgument(0), "修正 reference mismatch", 1));

        ProblemDraftResponse response = store.generate(99L, request);

        assertEquals("EXECUTION_VERIFIED", response.verificationStatus());
        assertEquals(1, response.repairAttemptCount());
        verify(aiProvider).generateProblemDraftStressGenerator(any(Long.class), eq(request), any(ProblemDraftResponse.class));
        verify(problemDraftRepairer).repair(any(ProblemDraftResponse.class), eq(request), any(), eq(1), eq(5));
    }

    @Test
    void generateDefaultsReferenceCheckForHighRatingWhenUnset() {
        ProblemDraftRequest request = requestWithCfOnly(1700);
        ProblemDraftResponse generated = withReferenceSolver(validGeneratedDraft(
                23L,
                "High rating reference",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(aiProvider.generateProblemDraft(any(Long.class), eq(request))).thenReturn(generated);
        when(aiProvider.generateProblemDraftStressGenerator(any(Long.class), eq(request), any(ProblemDraftResponse.class)))
                .thenReturn(new ProblemDraftStressGeneratorResult(
                        "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                        "stress-model",
                        2,
                        3
                ));
        when(draftSandboxClient.verifyDraftDetailed(any(ProblemDraftResponse.class), any()))
                .thenReturn(new DraftExecutionReport(new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()),
                        CrossCheckReport.passed(3)));

        ProblemDraftResponse response = store.generate(99L, request);

        assertEquals("EXECUTION_VERIFIED", response.verificationStatus());
        verify(aiProvider).generateProblemDraftStressGenerator(any(Long.class), eq(request), any(ProblemDraftResponse.class));
        verify(draftSandboxClient).verifyDraftDetailed(any(ProblemDraftResponse.class), any());
    }

    @Test
    void generateDoesNotDefaultReferenceCheckWhenHighRatingExplicitlyDisabled() {
        ProblemDraftRequest request = new ProblemDraftRequest(
                "数组",
                null,
                1700,
                "训练输入输出",
                "哈希",
                List.of("哈希", "数组"),
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false
        );
        when(aiProvider.generateProblemDraft(any(Long.class), eq(request))).thenReturn(validGeneratedDraft(
                24L,
                "High rating no reference",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));

        ProblemDraftResponse response = store.generate(99L, request);

        assertEquals("VALID", response.validationStatus());
        verify(aiProvider, never()).generateProblemDraftStressGenerator(any(Long.class), any(), any(ProblemDraftResponse.class));
    }

    @Test
    void generateMarksVerificationFailedWhenComplexityAuditFails() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                24L,
                "Complexity fails",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(draftSandboxClient.verifyDraftDetailed(any(ProblemDraftResponse.class), any()))
                .thenReturn(new DraftExecutionReport(
                        new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()),
                        null,
                        ComplexityReport.of("O(n^2)", "O(n^2)", List.of(),
                                List.of(new VerificationError("COMPLEXITY_CONSTRAINT_MISMATCH",
                                        "declared complexity O(n^2) is too high", "generationPlan")),
                                List.of())
                ));

        ProblemDraftResponse response = store.generate(99L, request());

        assertEquals("FAILED", response.verificationStatus());
        assertNotNull(response.verificationReportJson());
        assertTrue(response.verificationReportJson().contains("\"complexityReport\""));
        assertTrue(response.verificationReportJson().contains("COMPLEXITY_CONSTRAINT_MISMATCH"));
        assertTrue(response.verificationReportJson().contains("\"failureClassification\""));
        assertTrue(response.verificationReportJson().contains("STANDARD_COMPLEXITY_OR_RUNTIME"));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generateAttemptsRepairWhenStaticValidationFailsAndAutoRepairIsEnabled() {
        ProblemDraftRequest request = requestWithAutoRepair();
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                21L,
                "Missing fields",
                "",
                "",
                "",
                List.of(new TestCaseDto("1\n", "", true))
        ));
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), eq(request), any(), eq(1), eq(5)))
                .thenReturn(validGeneratedDraft(
                        21L,
                        "Static repair succeeds",
                        "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                        "int main(){return 0;}",
                        "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                        validCases()
                ));
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()));

        ProblemDraftResponse response = store.generate(99L, request);

        assertEquals("EXECUTION_VERIFIED", response.verificationStatus());
        assertEquals(1, response.repairAttemptCount());
        verify(problemDraftRepairer).repair(any(ProblemDraftResponse.class), eq(request), any(), eq(1), eq(5));
        verify(draftSandboxClient, times(1)).verifyDraft(any(ProblemDraftResponse.class), any());
    }

    @Test
    void generateStopsRepairLoopAtConfiguredMaximum() {
        ProblemDraftRequest request = requestWithAutoRepair();
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                18L,
                "Repair exhausted",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(failedSandboxReport())
                .thenReturn(failedSandboxReport())
                .thenReturn(failedSandboxReport());
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), eq(request), any(), anyInt(), eq(5)))
                .thenAnswer(invocation -> {
                    int attempt = invocation.getArgument(3);
                    return repairedDraft(invocation.getArgument(0), "修复第 " + attempt + " 次", attempt);
                });

        ProblemDraftResponse response = store.generate(99L, request);

        assertEquals("FAILED", response.verificationStatus());
        assertEquals(5, response.repairAttemptCount());
        assertEquals("修复第 5 次", response.lastRepairReason());
        verify(problemDraftRepairer, times(5)).repair(any(ProblemDraftResponse.class), eq(request), any(), anyInt(), eq(5));
    }

    @Test
    void generateKeepsDraftWhenRepairThrows() {
        ProblemDraftRequest request = requestWithAutoRepair();
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(validGeneratedDraft(
                19L,
                "Repair throws",
                "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any())).thenReturn(failedSandboxReport());
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), eq(request), any(), eq(1), eq(5)))
                .thenThrow(new IllegalStateException("provider down"));

        ProblemDraftResponse response = store.generate(99L, request);

        assertEquals("FAILED", response.verificationStatus());
        assertEquals(1, response.repairAttemptCount());
        assertTrue(response.lastRepairReason().contains("provider down"));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void generateDerivesDifficultyFromCfRatingWhenDifficultyIsMissing() {
        when(aiProvider.generateProblemDraft(any(Long.class), any(ProblemDraftRequest.class))).thenReturn(new ProblemDraftResponse(
                20L,
                "PENDING_REVIEW",
                "Rating derived draft",
                null,
                "题目描述 给定数组，输出第一个重复元素。输入描述 第一行一个整数 n。输出描述 输出答案。",
                "notes",
                "cpp",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                "plan",
                List.of("哈希", "数组"),
                "VALID",
                List.of(),
                validCases(),
                1000,
                262144,
                null,
                "mock",
                10,
                20,
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null
        ));

        ProblemDraftResponse response = store.generate(99L, requestWithCfOnly(1500));

        assertEquals("VALID", response.validationStatus());
        assertEquals("MEDIUM", response.difficulty());
        assertTrue(response.validationErrors().isEmpty());
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void importRejectsApprovedInvalidDraftWithoutCallingProblemService() {
        ProblemDraftEntity draft = draft(14L);
        draft.setStatus("APPROVED");
        draft.setValidationStatus("INVALID");
        when(problemDraftMapper.selectById(14L)).thenReturn(draft);

        assertThrows(DomainException.class,
                () -> store.approve(14L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void importRejectsApprovedDraftWithFailedVerificationWithoutCallingProblemService() {
        ProblemDraftEntity draft = draft(15L);
        draft.setStatus("APPROVED");
        draft.setValidationStatus("VALID");
        draft.setVerificationStatus("FAILED");
        when(problemDraftMapper.selectById(15L)).thenReturn(draft);

        assertThrows(DomainException.class,
                () -> store.approve(15L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void importRejectsApprovedDraftWithoutExecutionVerificationWithoutCallingProblemService() {
        ProblemDraftEntity draft = draft(16L);
        draft.setStatus("APPROVED");
        draft.setValidationStatus("VALID");
        draft.setVerificationStatus("NOT_RUN");
        when(problemDraftMapper.selectById(16L)).thenReturn(draft);

        assertThrows(DomainException.class,
                () -> store.approve(16L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void manualApproveAllowsDraftWithFailedAutoTests() {
        ProblemDraftEntity draft = draft(17L);
        draft.setStatus("PENDING_REVIEW");
        draft.setValidationStatus("INVALID");
        draft.setVerificationStatus("FAILED");
        when(problemDraftMapper.selectById(17L)).thenReturn(draft);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        store.approve(17L, 99L, new ProblemDraftApprovalRequest(false, null), "Bearer token");

        verifyNoInteractions(problemServiceClient);
        assertEquals("APPROVED", draft.getStatus());
        verify(problemDraftMapper).updateById(draft);
    }

    @Test
    void manualReviewReplacesFailedVerificationWithManualRecord() {
        ProblemDraftEntity draft = draft(18L);
        draft.setStatus("PENDING_REVIEW");
        draft.setValidationStatus("INVALID");
        draft.setValidationErrors("[\"testCases: invalid\"]");
        draft.setVerificationStatus("FAILED");
        draft.setVerificationReportJson("{\"status\":\"FAILED\",\"sandboxReport\":{\"errors\":[{\"code\":\"SANDBOX_UNAVAILABLE\"}]}}");
        when(problemDraftMapper.selectById(18L)).thenReturn(draft);

        ProblemDraftResponse response = store.manualReview(18L, 99L);

        assertEquals("APPROVED", response.status());
        assertEquals("VALID", response.validationStatus());
        assertTrue(response.validationErrors().isEmpty());
        assertEquals("MANUAL_VERIFIED", response.verificationStatus());
        assertTrue(response.verificationReportJson().contains("MANUAL_REVIEW_PASSED"));
        assertFalse(response.verificationReportJson().contains("SANDBOX_UNAVAILABLE"));
        verify(problemDraftMapper).updateById(draft);
        verifyNoInteractions(problemServiceClient);
    }

    @Test
    void manualReviewRejectsAlreadyImportedDraft() {
        ProblemDraftEntity draft = draft(19L);
        draft.setImportedProblemId(700L);
        when(problemDraftMapper.selectById(19L)).thenReturn(draft);

        assertThrows(DomainException.class, () -> store.manualReview(19L, 99L));

        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void manualReviewSucceedsWithoutTestcasePackage() {
        ProblemDraftEntity draft = draft(20L);
        draft.setVerificationStatus("FAILED");
        when(problemDraftMapper.selectById(20L)).thenReturn(draft);

        ProblemDraftResponse response = store.manualReview(20L, 99L);

        assertEquals("APPROVED", response.status());
        assertEquals("MANUAL_VERIFIED", response.verificationStatus());
        verify(problemDraftMapper).updateById(draft);
        verifyNoInteractions(problemServiceClient);
    }

    @Test
    void importManualVerifiedDraftWithoutArtifactSkipsTestcaseUpload() {
        ProblemDraftEntity draft = draft(21L);
        draft.setStatus("APPROVED");
        draft.setValidationStatus("VALID");
        draft.setVerificationStatus("MANUAL_VERIFIED");
        when(problemDraftMapper.selectById(21L)).thenReturn(draft);
        when(testcaseArtifactService.latestReady(21L)).thenReturn(null);
        when(problemServiceClient.createProblem(any(ProblemDraftResponse.class), eq("Bearer token"), any())).thenReturn(501L);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        ProblemDraftResponse response = store.approve(21L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token");

        assertEquals(Long.valueOf(501L), response.importedProblemId());
        verify(problemServiceClient).createProblem(any(ProblemDraftResponse.class), eq("Bearer token"), any());
        verify(problemServiceClient, never()).uploadAndActivateTestcasePackage(anyLong(), any(), any(), anyString());
        verify(testcaseArtifactService, never()).markImported(anyLong(), anyLong(), anyLong());
    }

    @Test
    void approveImportCarriesStandardSolutionAndGeneratorFields() {
        ProblemDraftEntity draft = draft(5L);
        draft.setStatus("APPROVED");
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        ProblemDraftTestcaseArtifactEntity artifact = readyArtifact(50L, 5L);
        when(problemDraftMapper.selectById(5L)).thenReturn(draft);
        when(testcaseArtifactService.latestReady(5L)).thenReturn(artifact);
        when(testcaseArtifactService.resolvePath(artifact)).thenReturn(Path.of("D:/tmp/official-hidden.zip"));
        when(problemServiceClient.createProblem(any(ProblemDraftResponse.class), eq("Bearer token"), any())).thenReturn(500L);
        when(problemServiceClient.uploadAndActivateTestcasePackage(eq(500L), eq(artifact), any(Path.class), eq("Bearer token")))
                .thenReturn(900L);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        store.approve(5L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token");

        ArgumentCaptor<ProblemDraftResponse> captor = ArgumentCaptor.forClass(ProblemDraftResponse.class);
        verify(problemServiceClient).createProblem(captor.capture(), eq("Bearer token"), any());
        assertEquals("python", captor.getValue().standardSolutionLanguage());
        assertEquals("print(sum(map(int, input().split())))", captor.getValue().standardSolutionCode());
        assertEquals("print('generated')", captor.getValue().testcaseGeneratorPython());
        assertEquals("分阶段生成", captor.getValue().generationPlan());
        verify(problemServiceClient).uploadAndActivateTestcasePackage(eq(500L), eq(artifact), any(Path.class), eq("Bearer token"));
        verify(testcaseArtifactService).markImported(50L, 500L, 900L);
    }

    @Test
    void refinePreservesGeneratedBlocksWhenRequestOmitsThem() {
        ProblemDraftEntity draft = draft(6L);
        draft.setImportedProblemId(600L);
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        when(problemDraftMapper.selectById(6L)).thenReturn(draft);
        when(problemServiceClient.updateProblem(eq(600L), any(ProblemDraftResponse.class), eq("Bearer token"), any())).thenReturn(600L);

        store.refine(6L, 99L, new ProblemDraftRefineRequest(
                "Refined title",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "只改标题"
        ), "Bearer token");

        ArgumentCaptor<ProblemDraftResponse> captor = ArgumentCaptor.forClass(ProblemDraftResponse.class);
        verify(problemServiceClient).updateProblem(eq(600L), captor.capture(), eq("Bearer token"), any());
        assertEquals("python", captor.getValue().standardSolutionLanguage());
        assertEquals("print(sum(map(int, input().split())))", captor.getValue().standardSolutionCode());
        assertEquals("print('generated')", captor.getValue().testcaseGeneratorPython());
        assertEquals("分阶段生成", captor.getValue().generationPlan());
    }

    @Test
    void refineRejectsImportedDraftWhenExecutionVerificationFails() {
        ProblemDraftEntity draft = draft(7L);
        draft.setImportedProblemId(700L);
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        when(problemDraftMapper.selectById(7L)).thenReturn(draft);
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(new VerificationReport("FAILED", List.of(new VerificationError(
                        "SANDBOX_SAMPLE_MISMATCH",
                        "sample output mismatch",
                        "testCases[0].expectedOutput"
                )), List.of()));

        assertThrows(DomainException.class, () -> store.refine(7L, 99L, new ProblemDraftRefineRequest(
                "Refined title",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "只改标题"
        ), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void regenerateOnlyRevalidatesSandboxUnavailableWithoutCallingProvider() {
        ProblemDraftEntity draft = draft(8L);
        draft.setTitle("逆序对统计");
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        draft.setVerificationReportJson("""
                {"sandboxReport":{"errors":[{"code":"SANDBOX_UNAVAILABLE","message":"localhost:8203 refused"}]}}
                """);
        when(problemDraftMapper.selectById(8L)).thenReturn(draft);

        ProblemDraftResponse response = store.regenerate(8L, 99L,
                new ProblemDraftRegenerateRequest("已修复 SANDBOX_UNAVAILABLE 问题，重新验证问题合理性"));

        assertEquals(8L, response.refinedFromDraftId());
        assertEquals("逆序对统计", response.title());
        verify(aiProvider, never()).regenerateProblemDraft(any(), any(), any());
        verify(problemDraftRepairer, never()).repair(any(), any(), any(), anyInt(), anyInt());
        ArgumentCaptor<ProblemDraftEntity> captor = ArgumentCaptor.forClass(ProblemDraftEntity.class);
        verify(problemDraftMapper).insert(captor.capture());
        assertEquals(8L, captor.getValue().getRefinedFromDraftId());
        assertTrue(captor.getValue().getDraftJson().contains("\"statement\":\"statement\""));
    }

    @Test
    void regenerateRepairsGeneratorFailureWithoutChangingTopicOrTags() {
        ProblemDraftEntity draft = draft(9L);
        draft.setTitle("找到那个数字");
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        draft.setVerificationReportJson("""
                {"sandboxReport":{"errors":[{"code":"GENERATOR_PYTHON_FAILED","field":"testcaseGeneratorPython","message":"NameError: std_exe is not defined"}]}}
                """);
        when(problemDraftMapper.selectById(9L)).thenReturn(draft);
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), any(ProblemDraftRequest.class), any(), eq(1), eq(5)))
                .thenAnswer(invocation -> {
                    ProblemDraftResponse source = invocation.getArgument(0);
                    return new ProblemDraftResponse(
                            source.id(),
                            source.status(),
                            source.title(),
                            source.difficulty(),
                            source.statement(),
                            source.notes(),
                            source.standardSolutionLanguage(),
                            source.standardSolutionCode(),
                            source.referenceSolutionLanguage(),
                            source.referenceSolutionCode(),
                            "from pathlib import Path\nstd_exe = compile_std()\nwrite_case(1, '1\\n', std_exe)",
                            source.stressTestcaseGeneratorPython(),
                            source.generationPlan(),
                            source.tags(),
                            source.validationStatus(),
                            source.validationErrors(),
                            source.testCases(),
                            source.timeLimitMillis(),
                            source.memoryLimitKb(),
                            source.importedProblemId(),
                            "repair-model",
                            5,
                            6,
                            source.createdAt(),
                            source.archivedAt(),
                            source.deletedAt(),
                            source.deletedBy(),
                            source.refinedFromDraftId(),
                            source.refineNote(),
                            "NOT_RUN",
                            null,
                            1,
                            "只修官方生成器"
                    );
                });

        ProblemDraftResponse response = store.regenerate(9L, 99L,
                new ProblemDraftRegenerateRequest(
                        "只修复官方隐藏点生成器里的 std_exe 未定义问题，不修改题目主题、标签、算法、题干"));

        assertEquals(9L, response.refinedFromDraftId());
        assertEquals("找到那个数字", response.title());
        assertEquals(List.of("implementation"), response.tags());
        assertTrue(response.testcaseGeneratorPython().contains("std_exe = compile_std()"));
        verify(aiProvider, never()).regenerateProblemDraft(any(), any(), any());
        verify(problemDraftRepairer).repair(any(ProblemDraftResponse.class), any(ProblemDraftRequest.class), any(), eq(1), eq(5));
    }

    @Test
    void regenerateGeneratesMissingStressGeneratorBeforeStaticValidation() {
        ProblemDraftEntity draft = draft(10L);
        draft.setTitle("计算逆序对总数");
        draft.setDraftJson(draftJsonWithReferenceSolverWithoutStress());
        draft.setVerificationReportJson("""
                {"complexityReport":{"errors":[{"code":"DATA_RANGE_OUTPUT_UNBOUNDED","field":"statement","message":"output range is unbounded"}]}}
                """);
        when(problemDraftMapper.selectById(10L)).thenReturn(draft);
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), any(ProblemDraftRequest.class), any(), eq(1), eq(5)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(aiProvider.generateProblemDraftStressGenerator(any(Long.class), any(ProblemDraftRequest.class),
                any(ProblemDraftResponse.class))).thenReturn(new ProblemDraftStressGeneratorResult(
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)\n(Path('testcases') / 'stress_small_001.in').write_text('1\\n')\n(Path('testcases') / 'stress_small_001.out').write_text('0\\n')",
                "stress-model",
                2,
                3
        ));

        ProblemDraftResponse response = store.regenerate(10L, 99L,
                new ProblemDraftRegenerateRequest(
                        "只修复 DATA_RANGE_OUTPUT_UNBOUNDED 的输出规模和题面规格，不修改主题、标签、算法"));

        assertEquals(10L, response.refinedFromDraftId());
        assertTrue(response.stressTestcaseGeneratorPython().contains("stress_small_001"));
        verify(aiProvider).generateProblemDraftStressGenerator(any(Long.class), any(ProblemDraftRequest.class),
                any(ProblemDraftResponse.class));
        ArgumentCaptor<ProblemDraftEntity> captor = ArgumentCaptor.forClass(ProblemDraftEntity.class);
        verify(problemDraftMapper).insert(captor.capture());
        assertTrue(captor.getValue().getDraftJson().contains("stress_small_001"));
    }

    @Test
    void regenerateFailsWithoutPersistingWhenMissingStressGeneratorCannotBeGenerated() {
        ProblemDraftEntity draft = draft(11L);
        draft.setTitle("计算逆序对总数");
        draft.setDraftJson(draftJsonWithReferenceSolverWithoutStress());
        draft.setVerificationReportJson("""
                {"complexityReport":{"errors":[{"code":"DATA_RANGE_OUTPUT_UNBOUNDED","field":"statement","message":"output range is unbounded"}]}}
                """);
        when(problemDraftMapper.selectById(11L)).thenReturn(draft);
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), any(ProblemDraftRequest.class), any(), eq(1), eq(5)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(aiProvider.generateProblemDraftStressGenerator(any(Long.class), any(ProblemDraftRequest.class),
                any(ProblemDraftResponse.class))).thenReturn(new ProblemDraftStressGeneratorResult(
                "",
                "stress-model",
                2,
                3
        ));

        DomainException error = assertThrows(DomainException.class, () -> store.regenerate(11L, 99L,
                new ProblemDraftRegenerateRequest(
                        "只修复 DATA_RANGE_OUTPUT_UNBOUNDED 的输出规模和题面规格，不修改主题、标签、算法")));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode());
        assertTrue(error.getMessage().contains("REFERENCE_GENERATOR_REQUIRED"));
        verify(problemDraftMapper, never()).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void regenerateAutoRepairsFailedRewriteBeforePersisting() {
        ProblemDraftEntity draft = draft(13L);
        draft.setTitle("逆序对统计");
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        when(problemDraftMapper.selectById(13L)).thenReturn(draft);
        when(aiProvider.regenerateProblemDraft(any(), any(), any())).thenReturn(validGeneratedDraft(
                100L,
                "逆序对统计",
                "题目描述 给定数组，输出逆序对数量。输入描述 第一行 n。输出描述 输出一个整数。",
                "print(0)",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(failedSandboxReport())
                .thenReturn(new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()));
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), any(ProblemDraftRequest.class), any(), eq(1), eq(5)))
                .thenAnswer(invocation -> repairedDraft(invocation.getArgument(0), "修正样例和标程", 1));

        ProblemDraftResponse response = store.regenerate(13L, 99L,
                new ProblemDraftRegenerateRequest("修复样例输出、标程复杂度和数据范围说明，不修改主题标签算法"));

        assertEquals("EXECUTION_VERIFIED", response.verificationStatus());
        assertEquals(1, response.repairAttemptCount());
        assertEquals("修正样例和标程", response.lastRepairReason());
        verify(problemDraftRepairer).repair(any(ProblemDraftResponse.class), any(ProblemDraftRequest.class), any(), eq(1), eq(5));
        verify(problemDraftMapper).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void regenerateFailsJobWithoutPersistingWhenAutoRepairIsExhausted() {
        ProblemDraftEntity draft = draft(14L);
        draft.setTitle("逆序对统计");
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        when(problemDraftMapper.selectById(14L)).thenReturn(draft);
        when(aiProvider.regenerateProblemDraft(any(), any(), any())).thenReturn(validGeneratedDraft(
                101L,
                "逆序对统计",
                "题目描述 给定数组，输出逆序对数量。输入描述 第一行 n。输出描述 输出一个整数。",
                "print(0)",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                validCases()
        ));
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(failedSandboxReport());
        when(problemDraftRepairer.repair(any(ProblemDraftResponse.class), any(ProblemDraftRequest.class), any(), anyInt(), eq(5)))
                .thenAnswer(invocation -> {
                    int attempt = invocation.getArgument(3);
                    return repairedDraft(invocation.getArgument(0), "修复第 " + attempt + " 次", attempt);
                });

        DomainException error = assertThrows(DomainException.class, () -> store.regenerate(14L, 99L,
                new ProblemDraftRegenerateRequest("修复样例输出、标程复杂度和数据范围说明，不修改主题标签算法")));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        assertTrue(error.getMessage().contains("after 5 auto repair attempts"));
        verify(problemDraftRepairer, times(5)).repair(any(ProblemDraftResponse.class), any(ProblemDraftRequest.class),
                any(), anyInt(), eq(5));
        verify(problemDraftMapper, never()).insert(any(ProblemDraftEntity.class));
    }

    @Test
    void regenerateFailsWithoutPersistingWhenProviderReturnsInvalidDraft() {
        ProblemDraftEntity draft = draft(12L);
        draft.setTitle("需要改写的题");
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        draft.setVerificationReportJson(null);
        when(problemDraftMapper.selectById(12L)).thenReturn(draft);
        when(aiProvider.regenerateProblemDraft(any(), any(), any())).thenReturn(new ProblemDraftResponse(
                100L,
                "PENDING_REVIEW",
                "需要改写的题",
                "EASY",
                "",
                null,
                "cpp",
                "",
                "",
                "AI provider failed before a valid staged draft could be produced.",
                List.of(),
                "INVALID",
                List.of("Provider returned invalid regenerated draft JSON: Unbalanced JSON object"),
                List.of(),
                1000,
                262144,
                null,
                "mock",
                3,
                4,
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null
        ));

        DomainException error = assertThrows(DomainException.class, () -> store.regenerate(12L, 99L,
                new ProblemDraftRegenerateRequest("整体润色题面表达")));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode());
        verify(problemDraftMapper, never()).insert(any(ProblemDraftEntity.class));
        verify(draftSandboxClient, never()).verifyDraft(any(ProblemDraftResponse.class), any());
        verify(problemDraftRepairer, never()).repair(any(), any(), any(), anyInt(), anyInt());
    }

    private void authenticateAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(99L, "admin", Set.of(Role.ADMIN)),
                null
        ));
    }

    private ProblemDraftRequest request() {
        return new ProblemDraftRequest(
                "数组",
                "EASY",
                null,
                "训练输入输出",
                "前缀和",
                null,
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private ProblemDraftRequest requestWithCfOnly(int cfRating) {
        return new ProblemDraftRequest(
                "数组",
                null,
                cfRating,
                "训练输入输出",
                "哈希",
                List.of("哈希", "数组"),
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private ProblemDraftRequest requestWithAutoRepair() {
        return new ProblemDraftRequest(
                "数组",
                "EASY",
                null,
                "训练输入输出",
                "前缀和",
                null,
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null
        );
    }

    private ProblemDraftRequest requestWithAutoRepairAndReferenceCheck() {
        return new ProblemDraftRequest(
                "数组",
                "EASY",
                null,
                "训练输入输出",
                "前缀和",
                null,
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                true
        );
    }

    private VerificationReport failedSandboxReport() {
        return new VerificationReport("FAILED", List.of(new VerificationError(
                "SANDBOX_SAMPLE_MISMATCH",
                "sample case 2 output mismatch",
                "testCases[1].expectedOutput"
        )), List.of());
    }

    private DraftExecutionReport referenceMismatchExecutionReport() {
        return new DraftExecutionReport(
                new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()),
                CrossCheckReport.failed(1,
                        List.of(new CrossCheckMismatch("stress_small_001.in", "1 2", "4", "3")),
                        List.of(new VerificationError("REFERENCE_MISMATCH",
                                "reference check mismatch on stress_small_001.in", "standardSolutionCode")),
                        List.of())
        );
    }

    private ProblemDraftResponse repairedDraft(ProblemDraftResponse source, String reason, int attempt) {
        return new ProblemDraftResponse(
                source.id(),
                source.status(),
                source.title(),
                source.difficulty(),
                source.statement(),
                source.notes(),
                source.standardSolutionLanguage(),
                source.standardSolutionCode(),
                source.referenceSolutionLanguage(),
                source.referenceSolutionCode(),
                source.testcaseGeneratorPython(),
                source.stressTestcaseGeneratorPython(),
                source.generationPlan(),
                source.tags(),
                source.validationStatus(),
                source.validationErrors(),
                source.testCases(),
                source.timeLimitMillis(),
                source.memoryLimitKb(),
                source.importedProblemId(),
                source.model(),
                source.promptTokens() + 1,
                source.completionTokens() + 1,
                source.createdAt(),
                source.archivedAt(),
                source.deletedAt(),
                source.deletedBy(),
                source.refinedFromDraftId(),
                source.refineNote(),
                "NOT_RUN",
                null,
                attempt,
                reason
        );
    }

    private ProblemDraftResponse withReferenceSolver(ProblemDraftResponse source) {
        return new ProblemDraftResponse(
                source.id(),
                source.status(),
                source.title(),
                source.difficulty(),
                source.statement(),
                source.notes(),
                source.standardSolutionLanguage(),
                source.standardSolutionCode(),
                "cpp",
                "int main(){return 0;}",
                source.testcaseGeneratorPython(),
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                source.generationPlan(),
                source.tags(),
                source.validationStatus(),
                source.validationErrors(),
                source.testCases(),
                source.timeLimitMillis(),
                source.memoryLimitKb(),
                source.importedProblemId(),
                source.model(),
                source.promptTokens(),
                source.completionTokens(),
                source.createdAt(),
                source.archivedAt(),
                source.deletedAt(),
                source.deletedBy(),
                source.refinedFromDraftId(),
                source.refineNote()
        );
    }

    private ProblemDraftResponse validGeneratedDraft(
            Long id,
            String title,
            String statement,
            String standardSolutionCode,
            String testcaseGeneratorPython,
            List<TestCaseDto> testCases
    ) {
        return new ProblemDraftResponse(
                id,
                "PENDING_REVIEW",
                title,
                "EASY",
                statement,
                "notes",
                "cpp",
                standardSolutionCode,
                testcaseGeneratorPython,
                "plan",
                List.of("implementation"),
                "VALID",
                List.of(),
                testCases,
                1000,
                262144,
                null,
                "mock",
                10,
                20,
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null
        );
    }

    private List<TestCaseDto> validCases() {
        return List.of(
                new TestCaseDto("1 2\n", "3\n", true),
                new TestCaseDto("2 3\n", "5\n", true),
                new TestCaseDto("10 20\n", "30\n", true)
        );
    }

    private ProblemDraftEntity draft(Long id) {
        ProblemDraftEntity draft = new ProblemDraftEntity();
        draft.setId(id);
        draft.setCreatorUserId(2L);
        draft.setTitle("Archived draft");
        draft.setDifficulty("EASY");
        draft.setDraftJson("""
                {
                  "statement": "statement",
                  "notes": "",
                  "tags": [],
                  "testCases": [],
                  "timeLimitMillis": 1000,
                  "memoryLimitKb": 262144,
                  "promptTokens": 0,
                  "completionTokens": 0
                }
                """);
        draft.setValidationStatus("VALID");
        draft.setValidationErrors("[]");
        draft.setVerificationStatus("EXECUTION_VERIFIED");
        draft.setModel("mock");
        draft.setStatus("PENDING_REVIEW");
        draft.setCreatedAt(LocalDateTime.parse("2026-06-01T00:00:00"));
        return draft;
    }

    private ProblemDraftTestcaseArtifactEntity readyArtifact(Long id, Long draftId) {
        ProblemDraftTestcaseArtifactEntity artifact = new ProblemDraftTestcaseArtifactEntity();
        artifact.setId(id);
        artifact.setDraftId(draftId);
        artifact.setStatus(ProblemDraftTestcaseArtifactService.STATUS_READY);
        artifact.setFileName("official-hidden.zip");
        artifact.setStoragePath("draft/official-hidden.zip");
        artifact.setFileSizeBytes(1024L);
        artifact.setSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        artifact.setCaseCount(3);
        artifact.setTotalInputBytes(18L);
        artifact.setTotalOutputBytes(8L);
        artifact.setLargestCaseBytes(8L);
        return artifact;
    }

    private String draftJsonWithGeneratedBlocks() {
        return """
                {
                  "statement": "statement",
                  "notes": "notes",
                  "standardSolutionLanguage": "python",
                  "standardSolutionCode": "print(sum(map(int, input().split())))",
                  "testcaseGeneratorPython": "print('generated')",
                  "generationPlan": "分阶段生成",
                  "tags": ["implementation"],
                  "testCases": [
                    {"input": "1 2\\n", "expectedOutput": "3\\n", "sample": true},
                    {"input": "2 3\\n", "expectedOutput": "5\\n", "sample": true},
                    {"input": "10 20\\n", "expectedOutput": "30\\n", "sample": true}
                  ],
                  "timeLimitMillis": 1000,
                  "memoryLimitKb": 262144,
                  "promptTokens": 11,
                  "completionTokens": 22
                }
                """;
    }

    private String draftJsonWithReferenceSolverWithoutStress() {
        return """
                {
                  "statement": "statement",
                  "notes": "notes",
                  "standardSolutionLanguage": "python",
                  "standardSolutionCode": "print(0)",
                  "referenceSolutionLanguage": "cpp",
                  "referenceSolutionCode": "int main(){return 0;}",
                  "testcaseGeneratorPython": "print('generated')",
                  "generationPlan": "分阶段生成",
                  "tags": ["implementation"],
                  "testCases": [
                    {"input": "1\\n", "expectedOutput": "0\\n", "sample": true},
                    {"input": "2\\n", "expectedOutput": "0\\n", "sample": true},
                    {"input": "3\\n", "expectedOutput": "0\\n", "sample": true}
                  ],
                  "timeLimitMillis": 1000,
                  "memoryLimitKb": 262144,
                  "promptTokens": 11,
                  "completionTokens": 22
                }
                """;
    }
}
