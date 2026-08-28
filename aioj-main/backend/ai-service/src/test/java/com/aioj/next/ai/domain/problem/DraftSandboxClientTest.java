package com.aioj.next.ai.domain.problem;

import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DraftSandboxClientTest {
    @Test
    void verifyDraftReportsCompileFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/compile"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "FAILED",
                            "message": "compile error",
                            "stderr": "missing semicolon"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        VerificationReport report = client.verifyDraft(draft("int main() {"), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorMessages()).contains("standardSolutionCode compile failed: compile error");
        server.verify();
    }

    @Test
    void verifyDraftReportsSampleMismatch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/compile"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "fileId": "compiled-main"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-one"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "stdout": "4\\n"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draft("int main(){return 0;}"), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorMessages()).contains("sample case 1 output mismatch: expected <3> but got <4>");
        server.verify();
    }

    @Test
    void verifyDraftReportsGeneratorFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andExpect(jsonPath("$.targetCaseCount").value(3))
                .andExpect(jsonPath("$.timeLimitMillis").value(30000))
                .andExpect(jsonPath("$.memoryLimitKb").value(524288))
                .andExpect(jsonPath("$.collectMode").value("OFFICIAL_PACKAGE"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "FAILED",
                            "message": "Nonzero Exit Status",
                            "stderr": "SyntaxError"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorMessages()).contains("testcaseGeneratorPython failed with status=FAILED: SyntaxError");
        server.verify();
    }

    @Test
    void verifyDraftReportsGeneratorFailureWithEnoughTracebackToDiagnoseExecutablePath() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "FAILED",
                            "message": "Nonzero Exit Status",
                            "stderr": "Traceback (most recent call last):\\n  File \\"/w/generator.py\\", line 132, in <module>\\n    main()\\n  File \\"/w/generator.py\\", line 77, in main\\n    write_case(1, \\"1\\\\n5\\\\n\\", exe_path)\\n  File \\"/w/generator.py\\", line 68, in write_case\\n    output_str = run_std(exe_path, input_str)\\n  File \\"/w/generator.py\\", line 59, in run_std\\n    proc = subprocess.run([str(exe_path)], input=input_str, capture_output=True, text=True, check=True)\\n  File \\"/usr/local/lib/python3.11/subprocess.py\\", line 548, in run\\n    with Popen(*popenargs, **kwargs) as process:\\nFileNotFoundError: [Errno 2] No such file or directory: 'std'",
                            "exitStatus": 1
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorMessages()).anySatisfy(message -> assertThat(message)
                .contains("testcaseGeneratorPython failed with status=FAILED exitStatus=1")
                .contains("run_std(exe_path, input_str)")
                .contains("FileNotFoundError")
                .contains("No such file or directory: 'std'"));
        server.verify();
    }

    @Test
    void verifyDraftReportsMissingGeneratedInputs() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andExpect(jsonPath("$.targetCaseCount").value(3))
                .andExpect(jsonPath("$.timeLimitMillis").value(30000))
                .andExpect(jsonPath("$.memoryLimitKb").value(524288))
                .andExpect(jsonPath("$.collectMode").value("OFFICIAL_PACKAGE"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "officialPackage": {
                              "status": "FAILED",
                              "errorCode": "GENERATOR_MISSING_INPUTS",
                              "errorMessage": "testcaseGeneratorPython generated 0 official .in cases, expected at least 3",
                              "caseCount": 0,
                              "generatedInputCount": 0,
                              "generatedOutputCount": 0
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorMessages()).anySatisfy(message -> assertThat(message)
                .contains("testcaseGeneratorPython failed with status=FAILED code=GENERATOR_MISSING_INPUTS")
                .contains("testcaseGeneratorPython generated 0 official .in cases, expected at least 3"));
        server.verify();
    }

    @Test
    void verifyDraftIncludesSandboxDetailWhenOfficialGeneratorPackageFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "FAILED",
                            "message": "Memory Limit Exceeded",
                            "stderr": "generator.py failed",
                            "exitStatus": 247,
                            "officialPackage": {
                              "status": "FAILED",
                              "errorCode": "GENERATOR_PYTHON_FAILED",
                              "errorMessage": "generator.py failed",
                              "caseCount": 0,
                              "generatedInputCount": 0,
                              "generatedOutputCount": 0
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorMessages()).anySatisfy(message -> assertThat(message)
                .contains("testcaseGeneratorPython failed with status=FAILED code=GENERATOR_PYTHON_FAILED")
                .contains("Memory Limit Exceeded")
                .contains("generator.py failed")
                .contains("exitStatus=247"));
        server.verify();
    }

    @Test
    void verifyDraftSendsRequestedTargetHiddenCaseCount() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andExpect(jsonPath("$.targetCaseCount").value(5))
                .andExpect(jsonPath("$.timeLimitMillis").value(50000))
                .andExpect(jsonPath("$.memoryLimitKb").value(524288))
                .andExpect(jsonPath("$.collectMode").value("OFFICIAL_PACKAGE"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "FAILED",
                            "message": "Nonzero Exit Status",
                            "stderr": "SyntaxError"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(
                draftWithGenerator(),
                new VerificationOptions(List.of(), null, null, 5)
        );

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorMessages()).contains("testcaseGeneratorPython failed with status=FAILED: SyntaxError");
        server.verify();
    }

    @Test
    void verifyDraftMarksExecutionVerifiedWhenSamplesAndHiddenCasesPass() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        expectGeneratedHiddenFiles(server, 5, 100L, 120L, 130L);
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("EXECUTION_VERIFIED");
        assertThat(report.errorMessages()).isEmpty();
        assertThat(report.warningMessages()).contains("verified first 3 of 5 generated hidden inputs");
        server.verify();
    }

    @Test
    void verifyDraftMaterializesOutputsWhenGeneratorOnlyCreatesInputs() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        expectGeneratedInputOnlyHiddenFiles(server);
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("EXECUTION_VERIFIED");
        assertThat(report.errorMessages()).isEmpty();
        server.verify();
    }

    @Test
    void verifyDraftReportsStandardTleWhileMaterializingGeneratedInput() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        expectGeneratedOfficialPackageFailure(server, "STANDARD_TLE_ON_GENERATED_CASE",
                "standardSolutionCode exceeded time limit on 001.in");
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errors()).extracting(VerificationError::code)
                .contains("STANDARD_TLE_ON_GENERATED_CASE");
        assertThat(report.errors()).extracting(VerificationError::field)
                .contains("standardSolutionCode");
        server.verify();
    }

    @Test
    void verifyDraftReportsOfficialPackageTooLargeWithoutCopyOutLimitLeak() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        expectGeneratedOfficialPackageFailure(server, "GENERATOR_PACKAGE_TOO_LARGE",
                "official testcase package size 52428801 exceeds limit 52428800");
        expectDeleteCompiled(server);

        VerificationReport report = client.verifyDraft(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errors()).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo("GENERATOR_PACKAGE_TOO_LARGE");
            assertThat(error.field()).isEqualTo("testcaseGeneratorPython");
            assertThat(error.message()).contains("official testcase package size 52428801 exceeds limit 52428800");
        });
        assertThat(report.errorMessages()).noneMatch(message -> message.contains(".aioj_generated_files.json"));
        server.verify();
    }

    @Test
    void verifyDraftDetailedRecordsComplexityBenchmarkWarnings() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        expectGeneratedHiddenFiles(server, 3, 900L, 120L, 130L);
        expectDeleteCompiled(server);

        DraftExecutionReport report = client.verifyDraftDetailed(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.sandboxReport().status()).isEqualTo("EXECUTION_VERIFIED");
        assertThat(report.complexityReport()).isNotNull();
        assertThat(report.complexityReport().benchmarkRuns()).hasSize(3);
        assertThat(report.complexityReport().warnings()).extracting(VerificationWarning::code)
                .contains("COMPLEXITY_LOW_MARGIN");
        assertThat(report.passed()).isTrue();
        server.verify();
    }

    @Test
    void verifyDraftDetailedWarnsWhenBenchmarkMetricsAreMissing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        expectGeneratedHiddenFilesWithoutMetrics(server);
        expectDeleteCompiled(server);

        DraftExecutionReport report = client.verifyDraftDetailed(draftWithGenerator(), VerificationOptions.defaults(List.of()));

        assertThat(report.sandboxReport().status()).isEqualTo("EXECUTION_VERIFIED");
        assertThat(report.complexityReport()).isNotNull();
        assertThat(report.complexityReport().warnings()).extracting(VerificationWarning::code)
                .contains("COMPLEXITY_BENCHMARK_METRICS_MISSING");
        assertThat(report.passed()).isTrue();
        server.verify();
    }

    @Test
    void verifyDraftDetailedFailsComplexityWhenNaiveLoopConflictsWithLargeConstraints() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        expectGeneratedHiddenFiles(server, 3, 100L, 120L, 130L);
        expectDeleteCompiled(server);

        DraftExecutionReport report = client.verifyDraftDetailed(
                draftWithGenerator("""
                        int main() {
                          int n;
                          for (int i = 0; i < n; ++i) {
                            for (int j = 0; j < n; ++j) {
                            }
                          }
                          return 0;
                        }
                        """,
                        "constraints: n <= 200000; expectedTimeComplexity: O(n^2)"),
                new VerificationOptions(List.of(), null, 1800, 3, false)
        );

        assertThat(report.sandboxReport().status()).isEqualTo("EXECUTION_VERIFIED");
        assertThat(report.complexityReport()).isNotNull();
        assertThat(report.complexityReport().status()).isEqualTo("FAILED");
        assertThat(report.complexityReport().errors()).extracting(VerificationError::code)
                .contains("COMPLEXITY_CONSTRAINT_MISMATCH", "COMPLEXITY_RISK_HIGH_NAIVE_LOOP");
        assertThat(report.passed()).isFalse();
        server.verify();
    }

    @Test
    void verifyDraftDetailedFlagsLargeUnboundedRangeOutputAsSpecRisk() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server);
        expectRunAccepted(server, "3\n");
        expectGeneratedHiddenFiles(server, 3, 100L, 120L, 130L);
        expectDeleteCompiled(server);

        DraftExecutionReport report = client.verifyDraftDetailed(
                draftWithGenerator("""
                        int main() {
                          return 0;
                        }
                        """,
                        """
                        constraints: n,m <= 100000; expectedTimeComplexity: O(n log n)
                        Statement: operation 2 asks to 输出排序后的区间.
                        """),
                new VerificationOptions(List.of(), null, 1800, 3, false)
        );

        assertThat(report.complexityReport()).isNotNull();
        assertThat(report.complexityReport().status()).isEqualTo("FAILED");
        assertThat(report.complexityReport().errors()).extracting(VerificationError::code)
                .contains("DATA_RANGE_OUTPUT_UNBOUNDED");
        server.verify();
    }

    @Test
    void verifyDraftDetailedRunsReferenceCrossCheckWhenEnabled() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server, "compiled-main");
        expectRunAccepted(server, "3\n");
        expectGeneratedHiddenFiles(server);
        expectGeneratedStressFiles(server);
        expectCompileAccepted(server, "compiled-reference");
        expectRunAccepted(server, "3\n");
        expectRunAccepted(server, "3\n");
        expectRunAccepted(server, "5\n");
        expectRunAccepted(server, "5\n");
        expectRunAccepted(server, "30\n");
        expectRunAccepted(server, "30\n");
        expectDeleteCompiled(server);
        expectDeleteCompiled(server);

        DraftExecutionReport report = client.verifyDraftDetailed(
                draftWithGeneratorAndReference(),
                new VerificationOptions(List.of(), null, null, 3, true)
        );

        assertThat(report.sandboxReport().status()).isEqualTo("EXECUTION_VERIFIED");
        assertThat(report.crossCheckReport()).isNotNull();
        assertThat(report.crossCheckReport().status()).isEqualTo("PASSED");
        assertThat(report.crossCheckReport().caseCount()).isEqualTo(3);
        assertThat(report.passed()).isTrue();
        server.verify();
    }

    @Test
    void verifyDraftDetailedReportsReferenceMismatch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DraftSandboxClient client = new DraftSandboxClient(builder.build());

        expectCompileAccepted(server, "compiled-main");
        expectRunAccepted(server, "3\n");
        expectGeneratedHiddenFiles(server);
        expectGeneratedStressFiles(server);
        expectCompileAccepted(server, "compiled-reference");
        expectRunAccepted(server, "4\n");
        expectRunAccepted(server, "3\n");
        expectDeleteCompiled(server);
        expectDeleteCompiled(server);

        DraftExecutionReport report = client.verifyDraftDetailed(
                draftWithGeneratorAndReference(),
                new VerificationOptions(List.of(), null, null, 3, true)
        );

        assertThat(report.crossCheckReport()).isNotNull();
        assertThat(report.crossCheckReport().status()).isEqualTo("FAILED");
        assertThat(report.crossCheckReport().errors()).extracting(VerificationError::code)
                .contains("REFERENCE_MISMATCH");
        assertThat(report.crossCheckReport().mismatches()).hasSize(1);
        assertThat(report.crossCheckReport().mismatches().get(0).standardOutput()).isEqualTo("4");
        assertThat(report.crossCheckReport().mismatches().get(0).referenceOutput()).isEqualTo("3");
        assertThat(report.passed()).isFalse();
        server.verify();
    }

    private ProblemDraftResponse draft(String code) {
        return new ProblemDraftResponse(
                1L,
                "PENDING_REVIEW",
                "A+B",
                "EASY",
                "statement",
                "notes",
                "cpp",
                code,
                "",
                "plan",
                List.of("implementation"),
                "VALID",
                List.of(),
                List.of(new TestCaseDto("1 2\n", "3\n", true)),
                1000,
                262144,
                null,
                "mock",
                1,
                1,
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null
        );
    }

    private ProblemDraftResponse draftWithGenerator() {
        ProblemDraftResponse draft = draft("int main(){return 0;}");
        return draftWithGenerator(draft.standardSolutionCode(), draft.generationPlan());
    }

    private ProblemDraftResponse draftWithGenerator(String standardSolutionCode, String generationPlan) {
        ProblemDraftResponse draft = draft(standardSolutionCode);
        return new ProblemDraftResponse(
                draft.id(),
                draft.status(),
                draft.title(),
                draft.difficulty(),
                draft.statement(),
                draft.notes(),
                draft.standardSolutionLanguage(),
                draft.standardSolutionCode(),
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                generationPlan,
                draft.tags(),
                draft.validationStatus(),
                draft.validationErrors(),
                draft.testCases(),
                draft.timeLimitMillis(),
                draft.memoryLimitKb(),
                draft.importedProblemId(),
                draft.model(),
                draft.promptTokens(),
                draft.completionTokens(),
                draft.createdAt(),
                draft.archivedAt(),
                draft.deletedAt(),
                draft.deletedBy(),
                draft.refinedFromDraftId(),
                draft.refineNote()
        );
    }

    private ProblemDraftResponse draftWithGeneratorAndReference() {
        ProblemDraftResponse draft = draftWithGenerator();
        return new ProblemDraftResponse(
                draft.id(),
                draft.status(),
                draft.title(),
                draft.difficulty(),
                draft.statement(),
                draft.notes(),
                draft.standardSolutionLanguage(),
                draft.standardSolutionCode(),
                "cpp",
                "int main(){return 0;}",
                draft.testcaseGeneratorPython(),
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                draft.generationPlan(),
                draft.tags(),
                draft.validationStatus(),
                draft.validationErrors(),
                draft.testCases(),
                draft.timeLimitMillis(),
                draft.memoryLimitKb(),
                draft.importedProblemId(),
                draft.model(),
                draft.promptTokens(),
                draft.completionTokens(),
                draft.createdAt(),
                draft.archivedAt(),
                draft.deletedAt(),
                draft.deletedBy(),
                draft.refinedFromDraftId(),
                draft.refineNote()
        );
    }

    private void expectCompileAccepted(MockRestServiceServer server) {
        expectCompileAccepted(server, "compiled-main");
    }

    private void expectCompileAccepted(MockRestServiceServer server, String fileId) {
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/compile"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "fileId": "%s"
                          }
                        }
                        """.formatted(fileId), MediaType.APPLICATION_JSON));
    }

    private void expectRunAccepted(MockRestServiceServer server, String stdout) {
        expectRunAccepted(server, stdout, null, null);
    }

    private void expectRunAccepted(MockRestServiceServer server, String stdout, Long timeMillis, Long memoryKb) {
        String escapedStdout = stdout.replace("\\", "\\\\").replace("\n", "\\n");
        String metrics = "";
        if (timeMillis != null) {
            metrics += ",\n                            \"timeMillis\": " + timeMillis;
        }
        if (memoryKb != null) {
            metrics += ",\n                            \"memoryKb\": " + memoryKb;
        }
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-one"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "stdout": "%s"%s
                          }
                        }
                        """.formatted(escapedStdout, metrics), MediaType.APPLICATION_JSON));
    }

    private void expectDeleteCompiled(MockRestServiceServer server) {
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/delete-file"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": true
                        }
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectGeneratedHiddenFiles(MockRestServiceServer server) {
        expectGeneratedHiddenFiles(server, 3, 100L, 120L, 130L);
    }

    private void expectGeneratedHiddenFiles(MockRestServiceServer server, int generatedInputCount,
                                            Long time1, Long time2, Long time3) {
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andExpect(jsonPath("$.targetCaseCount").value(3))
                .andExpect(jsonPath("$.collectMode").value("OFFICIAL_PACKAGE"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "officialPackage": {
                              "status": "PASSED",
                              "packageFileId": "pkg-file",
                              "packageFileName": "official-hidden.zip",
                              "packageFileSizeBytes": 1024,
                              "packageSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                              "caseCount": 3,
                              "generatedInputCount": %d,
                              "generatedOutputCount": 3,
                              "totalInputBytes": 18,
                              "totalOutputBytes": 8,
                              "totalBytes": 26,
                              "largestCaseBytes": 8,
                              "cases": [
                                {"name":"001","inputPath":"testcases/001.in","outputPath":"testcases/001.out","inputBytes":4,"outputBytes":2,"status":"ACCEPTED","timeMillis":%d,"memoryKb":128,"message":"Accepted"},
                                {"name":"002","inputPath":"testcases/002.in","outputPath":"testcases/002.out","inputBytes":4,"outputBytes":2,"status":"ACCEPTED","timeMillis":%d,"memoryKb":128,"message":"Accepted"},
                                {"name":"003","inputPath":"testcases/003.in","outputPath":"testcases/003.out","inputBytes":8,"outputBytes":3,"status":"ACCEPTED","timeMillis":%d,"memoryKb":128,"message":"Accepted"}
                              ]
                            }
                          }
                        }
                        """.formatted(generatedInputCount, time1, time2, time3), MediaType.APPLICATION_JSON));
    }

    private void expectGeneratedInputOnlyHiddenFiles(MockRestServiceServer server) {
        expectGeneratedHiddenFiles(server);
    }

    private void expectGeneratedHiddenFilesWithoutMetrics(MockRestServiceServer server) {
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andExpect(jsonPath("$.targetCaseCount").value(3))
                .andExpect(jsonPath("$.collectMode").value("OFFICIAL_PACKAGE"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "officialPackage": {
                              "status": "PASSED",
                              "packageFileId": "pkg-file",
                              "packageFileName": "official-hidden.zip",
                              "packageFileSizeBytes": 1024,
                              "packageSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                              "caseCount": 3,
                              "generatedInputCount": 3,
                              "generatedOutputCount": 3,
                              "totalInputBytes": 18,
                              "totalOutputBytes": 8,
                              "totalBytes": 26,
                              "largestCaseBytes": 8,
                              "cases": [
                                {"name":"001","inputPath":"testcases/001.in","outputPath":"testcases/001.out","inputBytes":4,"outputBytes":2,"status":"ACCEPTED","message":"Accepted"},
                                {"name":"002","inputPath":"testcases/002.in","outputPath":"testcases/002.out","inputBytes":4,"outputBytes":2,"status":"ACCEPTED","message":"Accepted"},
                                {"name":"003","inputPath":"testcases/003.in","outputPath":"testcases/003.out","inputBytes":8,"outputBytes":3,"status":"ACCEPTED","message":"Accepted"}
                              ]
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectGeneratedOfficialPackageFailure(MockRestServiceServer server, String code, String message) {
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andExpect(jsonPath("$.targetCaseCount").value(3))
                .andExpect(jsonPath("$.collectMode").value("OFFICIAL_PACKAGE"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "officialPackage": {
                              "status": "FAILED",
                              "errorCode": "%s",
                              "errorMessage": "%s",
                              "caseCount": 0,
                              "generatedInputCount": 3,
                              "generatedOutputCount": 0
                            }
                          }
                        }
                        """.formatted(code, message), MediaType.APPLICATION_JSON));
    }

    private void expectGeneratedStressFiles(MockRestServiceServer server) {
        server.expect(once(), requestTo("http://judge/api/v1/internal/sandbox/run-python-script"))
                .andExpect(jsonPath("$.targetCaseCount").value(3))
                .andExpect(jsonPath("$.collectMode").value("PAIRED"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "status": "ACCEPTED",
                            "message": "Accepted",
                            "generatedFiles": {
                              "testcases/stress_small_001.in": "1 2\\n",
                              "testcases/stress_small_001.out": "3\\n",
                              "testcases/stress_small_002.in": "2 3\\n",
                              "testcases/stress_small_002.out": "5\\n",
                              "testcases/stress_small_003.in": "10 20\\n",
                              "testcases/stress_small_003.out": "30\\n"
                            },
                            "generatedPairCount": 3
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
    }
}
