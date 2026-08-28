package com.aioj.next.ai.domain.problem;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiProvider;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftRepairerTest {
    private static final String SAMPLE_MISMATCH_REPORT = """
            {"sandboxReport":{"errors":[{"code":"SANDBOX_SAMPLE_MISMATCH","field":"testCases[1].expectedOutput","message":"sample mismatch"}]}}
            """;
    private static final String REFERENCE_MISMATCH_REPORT = """
            {"crosscheckReport":{"errors":[{"code":"REFERENCE_MISMATCH","field":"referenceSolutionCode","message":"standard and reference differ"}]}}
            """;
    private static final String GENERATOR_FAILED_REPORT = """
            {"sandboxReport":{"errors":[{"code":"GENERATOR_PYTHON_FAILED","field":"testcaseGeneratorPython","message":"NameError"}]}}
            """;
    private static final String STANDARD_TLE_REPORT = """
            {"sandboxReport":{"errors":[{"code":"STANDARD_TLE_ON_GENERATED_CASE","field":"standardSolutionCode","message":"case 3 exceeded time limit"}]}}
            """;
    private static final String PROVIDER_SCHEMA_REPORT = """
            {"staticReport":{"errors":[{"code":"PROVIDER_VALIDATION_ERROR","field":"notes","message":"schema field missing"}]}}
            """;
    private static final String UNKNOWN_REPORT = """
            {"status":"FAILED"}
            """;

    @Mock
    private AiProvider aiProvider;

    private ProblemDraftRepairer repairer;

    @BeforeEach
    void setUp() {
        repairer = new ProblemDraftRepairer(aiProvider, new ObjectMapper(), new AiProperties());
    }

    @Test
    void repairAppliesAllowedPatchFieldsAndAccumulatesUsage() {
        ProblemDraftResponse draft = draft();
        List<TestCaseDto> fixedCases = List.of(
                new TestCaseDto("1 2\n", "3\n", true),
                new TestCaseDto("2 3\n", "5\n", true),
                new TestCaseDto("10 20\n", "30\n", true)
        );
        when(aiProvider.repairProblemDraft(eq(100L), eq(draft), anyString(), anyString(), eq(1), eq(2)))
                .thenReturn(new ProblemDraftRepairPatch(
                        List.of("standardSolutionCode", "testCases"),
                        "Ignored title",
                        null,
                        null,
                        null,
                        null,
                        "int main(){return 0;}",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        fixedCases,
                        null,
                        null,
                        "修正样例输出",
                        "repair-model",
                        7,
                        8
                ));

        ProblemDraftResponse repaired = repairer.repair(draft, request(), SAMPLE_MISMATCH_REPORT, 1, 2);

        assertThat(repaired.title()).isEqualTo("Original title");
        assertThat(repaired.standardSolutionCode()).isEqualTo("int main(){return 0;}");
        assertThat(repaired.testCases()).isEqualTo(fixedCases);
        assertThat(repaired.importedProblemId()).isEqualTo(900L);
        assertThat(repaired.promptTokens()).isEqualTo(17);
        assertThat(repaired.completionTokens()).isEqualTo(28);
        assertThat(repaired.model()).isEqualTo("repair-model");
        assertThat(repaired.verificationStatus()).isEqualTo("NOT_RUN");
        assertThat(repaired.verificationReportJson()).isNull();
        assertThat(repaired.repairAttemptCount()).isEqualTo(1);
        assertThat(repaired.lastRepairReason()).isEqualTo("修正样例输出");
    }

    @Test
    void repairIgnoresPatchFieldsNotListedInChangedFields() {
        ProblemDraftResponse draft = draft();
        when(aiProvider.repairProblemDraft(eq(100L), eq(draft), anyString(), anyString(), eq(1), eq(2)))
                .thenReturn(new ProblemDraftRepairPatch(
                        List.of("standardSolutionCode"),
                        null,
                        null,
                        "Mutated statement",
                        null,
                        null,
                        "fixed code",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "只修代码",
                        null,
                        0,
                        0
                ));

        ProblemDraftResponse repaired = repairer.repair(draft, request(), SAMPLE_MISMATCH_REPORT, 1, 2);

        assertThat(repaired.statement()).isEqualTo(draft.statement());
        assertThat(repaired.standardSolutionCode()).isEqualTo("fixed code");
    }

    @Test
    void repairAppliesReferenceSolverPatchWhenListed() {
        ProblemDraftResponse draft = draft();
        when(aiProvider.repairProblemDraft(eq(100L), eq(draft), anyString(), anyString(), eq(1), eq(2)))
                .thenReturn(new ProblemDraftRepairPatch(
                        List.of("referenceSolutionLanguage", "referenceSolutionCode", "stressTestcaseGeneratorPython"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "cpp",
                        "int main(){return 0;}",
                        null,
                        "from pathlib import Path\nPath('testcases/stress_small_001.in').write_text('1 2\\n')",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "补齐对拍暴力解",
                        null,
                        0,
                        0
                ));

        ProblemDraftResponse repaired = repairer.repair(draft, request(), REFERENCE_MISMATCH_REPORT, 1, 2);

        assertThat(repaired.referenceSolutionLanguage()).isEqualTo("cpp");
        assertThat(repaired.referenceSolutionCode()).isEqualTo("int main(){return 0;}");
        assertThat(repaired.stressTestcaseGeneratorPython()).contains("stress_small_001.in");
        assertThat(repaired.standardSolutionCode()).isEqualTo(draft.standardSolutionCode());
    }

    @Test
    void repairKeepsDraftWhenPatchIsEmpty() {
        ProblemDraftResponse draft = draft();
        when(aiProvider.repairProblemDraft(eq(100L), eq(draft), anyString(), anyString(), eq(2), eq(2)))
                .thenReturn(ProblemDraftRepairPatch.empty(null));

        ProblemDraftResponse repaired = repairer.repair(draft, request(), GENERATOR_FAILED_REPORT, 2, 2);

        assertThat(repaired.standardSolutionCode()).isEqualTo(draft.standardSolutionCode());
        assertThat(repaired.testCases()).isEqualTo(draft.testCases());
        assertThat(repaired.repairAttemptCount()).isEqualTo(2);
        assertThat(repaired.lastRepairReason()).isEqualTo("Auto repair attempt 2");
    }

    @Test
    void repairTruncatesReasonForDatabaseColumn() {
        ProblemDraftResponse draft = draft();
        String longReason = "x".repeat(1200);
        when(aiProvider.repairProblemDraft(eq(100L), eq(draft), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new ProblemDraftRepairPatch(
                        List.of("notes"),
                        null,
                        null,
                        null,
                        "new notes",
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
                        longReason,
                        null,
                        0,
                        0
                ));

        ProblemDraftResponse repaired = repairer.repair(draft, request(), PROVIDER_SCHEMA_REPORT, 1, 2);

        assertThat(repaired.notes()).isEqualTo("new notes");
        assertThat(repaired.lastRepairReason()).hasSize(1000);
    }

    @Test
    void repairRejectsStandardSolutionPatchForGeneratorFailure() {
        ProblemDraftResponse draft = draft();
        when(aiProvider.repairProblemDraft(eq(100L), eq(draft), anyString(), anyString(), eq(1), eq(2)))
                .thenReturn(new ProblemDraftRepairPatch(
                        List.of("standardSolutionCode", "testcaseGeneratorPython"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "should not apply",
                        null,
                        null,
                        "from pathlib import Path\nPath('testcases/001.in').write_text('1\\n')",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "只修官方生成器",
                        null,
                        0,
                        0
                ));

        ProblemDraftResponse repaired = repairer.repair(draft, request(), GENERATOR_FAILED_REPORT, 1, 2);

        assertThat(repaired.standardSolutionCode()).isEqualTo(draft.standardSolutionCode());
        assertThat(repaired.testcaseGeneratorPython()).contains("001.in");
    }

    @Test
    void repairRejectsGeneratorPatchForStandardTle() {
        ProblemDraftResponse draft = draft();
        when(aiProvider.repairProblemDraft(eq(100L), eq(draft), anyString(), anyString(), eq(1), eq(2)))
                .thenReturn(new ProblemDraftRepairPatch(
                        List.of("standardSolutionCode", "testcaseGeneratorPython"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "fixed standard",
                        null,
                        null,
                        "should not apply",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "只修标程",
                        null,
                        0,
                        0
                ));

        ProblemDraftResponse repaired = repairer.repair(draft, request(), STANDARD_TLE_REPORT, 1, 2);

        assertThat(repaired.standardSolutionCode()).isEqualTo("fixed standard");
        assertThat(repaired.testcaseGeneratorPython()).isEqualTo(draft.testcaseGeneratorPython());
    }

    @Test
    void repairBlocksUnknownLowConfidencePatch() {
        ProblemDraftResponse draft = draft();
        when(aiProvider.repairProblemDraft(eq(100L), eq(draft), anyString(), anyString(), eq(1), eq(2)))
                .thenReturn(new ProblemDraftRepairPatch(
                        List.of("notes"),
                        null,
                        null,
                        null,
                        "unsafe mutation",
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
                        "未知错误",
                        null,
                        0,
                        0
                ));

        ProblemDraftResponse repaired = repairer.repair(draft, request(), UNKNOWN_REPORT, 1, 2);

        assertThat(repaired.notes()).isEqualTo(draft.notes());
        assertThat(repaired.lastRepairReason()).isEqualTo("未知错误");
    }

    private ProblemDraftRequest request() {
        return new ProblemDraftRequest(
                "数组",
                "EASY",
                null,
                "训练输入输出",
                "implementation",
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

    private ProblemDraftResponse draft() {
        return new ProblemDraftResponse(
                100L,
                "PENDING_REVIEW",
                "Original title",
                "EASY",
                "题目描述。输入描述。输出描述。",
                "notes",
                "cpp",
                "old code",
                "old generator",
                "old plan",
                List.of("array"),
                "VALID",
                List.of(),
                List.of(
                        new TestCaseDto("1 2\n", "3\n", true),
                        new TestCaseDto("2 3\n", "4\n", true),
                        new TestCaseDto("10 20\n", "30\n", true)
                ),
                1000,
                262144,
                900L,
                "base-model",
                10,
                20,
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null,
                "FAILED",
                "{}",
                0,
                null
        );
    }
}
