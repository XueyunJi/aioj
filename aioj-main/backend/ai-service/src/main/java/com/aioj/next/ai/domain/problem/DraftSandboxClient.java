package com.aioj.next.ai.domain.problem;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DraftSandboxClient {
    private static final int DEFAULT_HIDDEN_CASE_COUNT = 3;
    private static final int MAX_HIDDEN_CASE_COUNT = 20;
    private static final int DEFAULT_STRESS_CASE_COUNT = 10;
    private static final int MAX_STRESS_CASE_COUNT = 30;
    private static final int MIN_GENERATOR_TIME_LIMIT_MILLIS = 30_000;
    private static final int MAX_GENERATOR_TIME_LIMIT_MILLIS = 180_000;
    private static final int MIN_GENERATOR_MEMORY_LIMIT_KB = 524_288;
    private static final int MATERIALIZED_OUTPUT_STDOUT_LIMIT_BYTES = 4 * 1024 * 1024;
    private static final double LOW_MARGIN_RATIO = 0.8d;
    private static final String COLLECT_MODE_PAIRED = "PAIRED";
    private static final String COLLECT_MODE_OFFICIAL_PACKAGE = "OFFICIAL_PACKAGE";
    private static final long DEFAULT_MAX_OFFICIAL_CASE_BYTES = 8L * 1024L * 1024L;
    private static final long DEFAULT_MAX_OFFICIAL_PACKAGE_BYTES = 50L * 1024L * 1024L;
    private static final Pattern BIG_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:1\\s*e\\s*5|2\\s*e\\s*5|5\\s*e\\s*4|10\\s*\\^\\s*5|2\\s*\\*\\s*10\\s*\\^\\s*5|100000|200000|50000)");
    private static final Pattern COMPLEXITY_PATTERN = Pattern.compile("(?i)O\\s*\\([^\\)]{1,80}\\)");
    private static final ParameterizedTypeReference<ApiResponse<CompileResponse>> COMPILE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<RunResponse>> RUN_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<PythonScriptResponse>> PYTHON_SCRIPT_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Boolean>> DELETE_FILE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final long maxOfficialCaseBytes;
    private final long maxOfficialPackageBytes;
    private ProblemDraftTestcaseArtifactService testcaseArtifactService;

    @Autowired
    public DraftSandboxClient(AiProperties properties, InternalApiProperties internalApiProperties) {
        this(RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getJudgeWorkerUri()))
                .defaultHeader("X-Internal-Token", internalApiProperties.getApiToken())
                .build(),
                properties.getProblemDraft().getMaxOfficialCaseBytes(),
                properties.getProblemDraft().getMaxOfficialPackageBytes());
    }

    DraftSandboxClient(RestClient restClient) {
        this(restClient, DEFAULT_MAX_OFFICIAL_CASE_BYTES, DEFAULT_MAX_OFFICIAL_PACKAGE_BYTES);
    }

    DraftSandboxClient(RestClient restClient, long maxOfficialCaseBytes, long maxOfficialPackageBytes) {
        this.restClient = restClient;
        this.maxOfficialCaseBytes = Math.max(1L, maxOfficialCaseBytes);
        this.maxOfficialPackageBytes = Math.max(1L, maxOfficialPackageBytes);
    }

    @Autowired(required = false)
    public void setTestcaseArtifactService(ProblemDraftTestcaseArtifactService testcaseArtifactService) {
        this.testcaseArtifactService = testcaseArtifactService;
    }

    public VerificationReport verifyDraft(ProblemDraftResponse draft, VerificationOptions options) {
        DraftExecutionReport report = verifyDraftDetailed(draft, options);
        return report == null ? null : report.sandboxReport();
    }

    public DraftExecutionReport verifyDraftDetailed(ProblemDraftResponse draft, VerificationOptions options) {
        List<VerificationError> errors = new ArrayList<>();
        List<VerificationWarning> warnings = new ArrayList<>();
        CrossCheckReport crossCheckReport = null;
            if (draft == null) {
            errors.add(error("SANDBOX_NO_DRAFT", "draft is required", null));
            return new DraftExecutionReport(report(errors, warnings), null);
        }
        CompileResponse compile = null;
        HiddenCaseVerificationResult hiddenResult = HiddenCaseVerificationResult.empty();
        ComplexityReport complexityReport = null;
        OfficialTestcasePackageReport officialPackageReport = null;
        try {
            compile = compile(draft);
            if (!"ACCEPTED".equals(compile.status())) {
                errors.add(error("SANDBOX_COMPILE_FAILED",
                        "standardSolutionCode compile failed: " + firstText(compile.message(), compile.stderr()),
                        "standardSolutionCode"));
                return new DraftExecutionReport(report(errors, warnings), null, null, null);
            }
            verifySamples(draft, compile, errors);
            hiddenResult = verifyGeneratorAndHiddenCases(draft, compile, options, errors, warnings);
            officialPackageReport = hiddenResult.officialPackageReport();
            complexityReport = auditComplexity(draft, options, hiddenResult);
            crossCheckReport = verifyReferenceCheck(draft, compile, options);
        } catch (RestClientException | IllegalStateException ex) {
            errors.add(error("SANDBOX_UNAVAILABLE", "sandbox verification failed: " + firstText(ex.getMessage(), ex.getClass().getSimpleName()), null));
        } finally {
            deleteCompiledFile(compile);
        }
        return new DraftExecutionReport(report(errors, warnings), crossCheckReport, complexityReport, officialPackageReport);
    }

    private CompileResponse compile(ProblemDraftResponse draft) {
        return compile(defaultLanguage(draft.standardSolutionLanguage()), draft.standardSolutionCode(),
                draft.timeLimitMillis(), draft.memoryLimitKb());
    }

    private CompileResponse compile(String language, String sourceCode, Integer timeLimitMillis, Integer memoryLimitKb) {
        return post("/api/v1/internal/sandbox/compile", new CompileRequest(
                defaultLanguage(language),
                sourceCode,
                timeLimitMillis,
                memoryLimitKb
        ), COMPILE_TYPE);
    }

    private void verifySamples(ProblemDraftResponse draft, CompileResponse compile, List<VerificationError> errors) {
        List<TestCaseDto> testCases = draft.testCases() == null ? List.of() : draft.testCases();
        for (int index = 0; index < testCases.size(); index++) {
            TestCaseDto testCase = testCases.get(index);
            RunResponse run = run(draft, compile.fileId(), testCase.input(), stdoutLimit(testCase.expectedOutput()));
            if (!"ACCEPTED".equals(run.status())) {
                errors.add(error("SANDBOX_SAMPLE_FAILED",
                        "sample case " + (index + 1) + " execution failed: " + firstText(run.message(), run.stderr()),
                        "testCases[" + index + "]"));
                continue;
            }
            if (!sameOutput(testCase.expectedOutput(), run.stdout())) {
                errors.add(error("SANDBOX_SAMPLE_MISMATCH",
                        "sample case " + (index + 1) + " output mismatch: expected <" + preview(testCase.expectedOutput())
                                + "> but got <" + preview(run.stdout()) + ">",
                        "testCases[" + index + "].expectedOutput"));
            }
        }
    }

    private HiddenCaseVerificationResult verifyGeneratorAndHiddenCases(ProblemDraftResponse draft, CompileResponse compile,
                                                                       VerificationOptions options, List<VerificationError> errors,
                                                                       List<VerificationWarning> warnings) {
        if (!StringUtils.hasText(draft.testcaseGeneratorPython())) {
            return HiddenCaseVerificationResult.empty();
        }
        int targetCount = targetHiddenCaseCount(options);
        PythonScriptResponse script = post("/api/v1/internal/sandbox/run-python-script", new PythonScriptRequest(
                draft.testcaseGeneratorPython(),
                generatorTimeLimitMillis(draft, targetCount),
                generatorMemoryLimitKb(draft),
                targetCount,
                COLLECT_MODE_OFFICIAL_PACKAGE,
                defaultLanguage(draft.standardSolutionLanguage()),
                draft.standardSolutionCode(),
                draft.timeLimitMillis(),
                draft.memoryLimitKb(),
                maxOfficialCaseBytes,
                maxOfficialPackageBytes
        ), PYTHON_SCRIPT_TYPE);
        OfficialTestcasePackageReport officialPackage = script.officialPackage();
        if (!"ACCEPTED".equals(script.status()) && officialPackage == null) {
            errors.add(error("GENERATOR_PYTHON_FAILED",
                    scriptFailureMessage("testcaseGeneratorPython", script),
                    "testcaseGeneratorPython"));
            return HiddenCaseVerificationResult.empty();
        }
        if (officialPackage == null) {
            errors.add(error("GENERATOR_PACKAGE_METADATA_INVALID",
                    "testcaseGeneratorPython did not return official testcase package metadata",
                    "testcaseGeneratorPython"));
            return HiddenCaseVerificationResult.empty();
        }
        if (!officialPackage.passed()) {
            String code = firstText(officialPackage.errorCode(), "GENERATOR_PYTHON_FAILED");
            errors.add(error(code, officialPackageFailureMessage("testcaseGeneratorPython", officialPackage, script),
                    officialPackageErrorField(code)));
            return new HiddenCaseVerificationResult(List.of(), generatedInputCount(script, officialPackage), officialPackage);
        }
        persistOfficialPackageArtifact(draft, officialPackage);
        List<ComplexityBenchmarkRun> benchmarkRuns = officialPackage.cases().stream()
                .sorted(Comparator.comparing(OfficialTestcaseCaseReport::name, Comparator.nullsLast(String::compareTo)))
                .map(item -> new ComplexityBenchmarkRun(
                        firstText(item.name(), item.inputPath()),
                        firstText(item.status(), "ACCEPTED"),
                        item.timeMillis(),
                        item.memoryKb(),
                        firstText(item.message(), "Accepted")))
                .toList();
        int generatedInputCount = generatedInputCount(script, officialPackage);
        if (generatedInputCount > benchmarkRuns.size()) {
            warnings.add(new VerificationWarning("HIDDEN_CASES_PARTIAL_VERIFICATION",
                    "verified first " + benchmarkRuns.size() + " of " + generatedInputCount
                            + " generated hidden inputs",
                    "testcaseGeneratorPython"));
        }
        return new HiddenCaseVerificationResult(benchmarkRuns, generatedInputCount, officialPackage);
    }

    private int generatedInputCount(PythonScriptResponse script, OfficialTestcasePackageReport officialPackage) {
        if (officialPackage != null && officialPackage.generatedInputCount() != null) {
            return officialPackage.generatedInputCount();
        }
        if (script != null && script.generatedInputCount() != null) {
            return script.generatedInputCount();
        }
        return officialPackage == null || officialPackage.caseCount() == null ? 0 : officialPackage.caseCount();
    }

    private void persistOfficialPackageArtifact(ProblemDraftResponse draft, OfficialTestcasePackageReport report) {
        if (testcaseArtifactService == null || draft == null || report == null || !report.passed()) {
            return;
        }
        testcaseArtifactService.storeFromOfficialPackage(draft.id(), null, report);
    }

    private List<ComplexityBenchmarkRun> materializeHiddenCaseOutputs(ProblemDraftResponse draft, CompileResponse compile,
                                                                      Map<String, HiddenCase> inputOnlyCases, int targetCount,
                                                                      List<VerificationError> errors) {
        List<ComplexityBenchmarkRun> benchmarkRuns = new ArrayList<>();
        List<HiddenCase> selectedInputs = inputOnlyCases.values().stream()
                .sorted(Comparator.comparing(HiddenCase::name))
                .toList();
        for (HiddenCase inputCase : selectedInputs) {
            RunResponse run = run(draft, compile.fileId(), inputCase.input(), MATERIALIZED_OUTPUT_STDOUT_LIMIT_BYTES);
            benchmarkRuns.add(new ComplexityBenchmarkRun(inputCase.name(), run.status(), run.timeMillis(), run.memoryKb(),
                    firstText(run.message(), run.stderr())));
            if (!"ACCEPTED".equals(run.status())) {
                String code = standardMaterializationErrorCode(run);
                errors.add(error(code,
                        "standardSolutionCode failed while materializing output for hidden case " + inputCase.name()
                                + ": " + firstText(run.message(), run.stderr()),
                        "standardSolutionCode"));
                return benchmarkRuns;
            }
            if (run.stdout() == null) {
                errors.add(error("STANDARD_OUTPUT_MATERIALIZATION_FAILED",
                        "standardSolutionCode produced no captured stdout while materializing output for hidden case "
                                + inputCase.name(),
                        "standardSolutionCode"));
                return benchmarkRuns;
            }
        }
        return benchmarkRuns;
    }

    private ComplexityReport auditComplexity(ProblemDraftResponse draft, VerificationOptions options,
                                             HiddenCaseVerificationResult hiddenResult) {
        List<VerificationError> errors = new ArrayList<>();
        List<VerificationWarning> warnings = new ArrayList<>();
        List<ComplexityBenchmarkRun> runs = hiddenResult == null ? List.of() : hiddenResult.benchmarkRuns();
        String claimed = claimedComplexity(draft == null ? null : draft.generationPlan());
        String inferred = inferredComplexity(draft == null ? null : draft.standardSolutionCode());
        boolean largeConstraints = hasLargeScaleText(draft == null ? null : draft.generationPlan())
                || hasLargeScaleText(draft == null ? null : draft.statement());
        if (largeConstraints && isNaiveHighComplexity(claimed)) {
            errors.add(error("COMPLEXITY_CONSTRAINT_MISMATCH",
                    "declared complexity " + claimed + " is too high for large constraints in generationPlan/statement",
                    "generationPlan"));
        }
        if (largeConstraints && isNaiveHighComplexity(inferred)) {
            errors.add(error("COMPLEXITY_RISK_HIGH_NAIVE_LOOP",
                    "standardSolutionCode appears to use high-order nested loops under large constraints",
                    "standardSolutionCode"));
        }
        if (largeConstraints && hasUnboundedBulkOutputRequirement(draft)) {
            errors.add(error("DATA_RANGE_OUTPUT_UNBOUNDED",
                    "statement requires outputting whole ranges or many elements under large constraints without a total output size bound",
                    "statement"));
        }
        int timeLimitMillis = draft == null || draft.timeLimitMillis() == null || draft.timeLimitMillis() <= 0
                ? 1000
                : draft.timeLimitMillis();
        for (ComplexityBenchmarkRun run : runs) {
            if (run == null) {
                continue;
            }
            if (isTimeLimitExceeded(run.status(), run.message())) {
                errors.add(error("COMPLEXITY_BENCHMARK_TLE",
                        "standardSolutionCode exceeded time limit during benchmark case " + run.name(),
                        "standardSolutionCode"));
                continue;
            }
            if (run.timeMillis() == null || run.memoryKb() == null) {
                warnings.add(new VerificationWarning("COMPLEXITY_BENCHMARK_METRICS_MISSING",
                        "benchmark case " + run.name() + " did not include complete time or memory metrics",
                        "standardSolutionCode"));
            }
            if (run.timeMillis() != null && run.timeMillis() >= Math.round(timeLimitMillis * LOW_MARGIN_RATIO)) {
                warnings.add(new VerificationWarning("COMPLEXITY_LOW_MARGIN",
                        "benchmark case " + run.name() + " used " + run.timeMillis()
                                + "ms of " + timeLimitMillis + "ms",
                        "standardSolutionCode"));
            }
        }
        if ((options != null && options.cfRating() != null && options.cfRating() >= 1700)
                && !StringUtils.hasText(claimed)) {
            warnings.add(new VerificationWarning("COMPLEXITY_CLAIM_MISSING",
                    "generationPlan should include the claimed time complexity for high-rating drafts",
                    "generationPlan"));
        }
        return ComplexityReport.of(claimed, inferred, runs, errors, warnings);
    }

    private RunResponse run(ProblemDraftResponse draft, String compiledFileId, String stdin, int stdoutLimitBytes) {
        return run(defaultLanguage(draft.standardSolutionLanguage()), draft.standardSolutionCode(), compiledFileId, stdin,
                draft.timeLimitMillis(), draft.memoryLimitKb(), stdoutLimitBytes);
    }

    private RunResponse run(String language, String sourceCode, String compiledFileId, String stdin,
                            Integer timeLimitMillis, Integer memoryLimitKb, int stdoutLimitBytes) {
        return post("/api/v1/internal/sandbox/run-one", new RunRequest(
                defaultLanguage(language),
                sourceCode,
                compiledFileId,
                stdin,
                timeLimitMillis,
                memoryLimitKb,
                stdoutLimitBytes
        ), RUN_TYPE);
    }

    private CrossCheckReport verifyReferenceCheck(ProblemDraftResponse draft, CompileResponse standardCompile,
                                                  VerificationOptions options) {
        if (options == null || !options.enableReferenceCheck()) {
            return null;
        }
        List<VerificationError> errors = new ArrayList<>();
        List<VerificationWarning> warnings = new ArrayList<>();
        List<CrossCheckMismatch> mismatches = new ArrayList<>();
        if (!StringUtils.hasText(draft.referenceSolutionCode())) {
            errors.add(error("REFERENCE_REQUIRED",
                    "referenceSolutionCode is required when reference check is enabled",
                    "referenceSolutionCode"));
            return CrossCheckReport.failed(0, mismatches, errors, warnings);
        }
        if (!StringUtils.hasText(draft.stressTestcaseGeneratorPython())) {
            errors.add(error("REFERENCE_GENERATOR_REQUIRED",
                    "stressTestcaseGeneratorPython is required for reference cross-check",
                    "stressTestcaseGeneratorPython"));
            return CrossCheckReport.failed(0, mismatches, errors, warnings);
        }
        int targetCount = targetStressCaseCount(options);
        PythonScriptResponse script = post("/api/v1/internal/sandbox/run-python-script", new PythonScriptRequest(
                draft.stressTestcaseGeneratorPython(),
                generatorTimeLimitMillis(draft, targetCount),
                generatorMemoryLimitKb(draft),
                targetCount,
                COLLECT_MODE_PAIRED
        ), PYTHON_SCRIPT_TYPE);
        if (!"ACCEPTED".equals(script.status())) {
            errors.add(error("REFERENCE_GENERATOR_FAILED",
                    scriptFailureMessage("stressTestcaseGeneratorPython", script),
                    "stressTestcaseGeneratorPython"));
            return CrossCheckReport.failed(0, mismatches, errors, warnings);
        }
        List<HiddenCase> stressCases = stressCases(script.generatedFiles()).values().stream()
                .sorted(Comparator.comparing(HiddenCase::name))
                .toList();
        if (stressCases.size() < DEFAULT_HIDDEN_CASE_COUNT) {
            errors.add(error("REFERENCE_INPUTS_REQUIRED",
                    missingGeneratedPairsMessage("stressTestcaseGeneratorPython", stressCases.size(), DEFAULT_HIDDEN_CASE_COUNT, script),
                    "stressTestcaseGeneratorPython"));
            return CrossCheckReport.failed(0, mismatches, errors, warnings);
        }
        int timeLimitMillis = crossCheckTimeLimitMillis(draft);
        int memoryLimitKb = crossCheckMemoryLimitKb(draft);
        CompileResponse referenceCompile = null;
        try {
            referenceCompile = compile(defaultLanguage(firstText(draft.referenceSolutionLanguage(), draft.standardSolutionLanguage())),
                    draft.referenceSolutionCode(), timeLimitMillis, memoryLimitKb);
            if (!"ACCEPTED".equals(referenceCompile.status())) {
                errors.add(error("REFERENCE_COMPILE_FAILED",
                        "referenceSolutionCode compile failed: " + firstText(referenceCompile.message(), referenceCompile.stderr()),
                        "referenceSolutionCode"));
                return CrossCheckReport.failed(0, mismatches, errors, warnings);
            }
            for (HiddenCase stressCase : stressCases) {
                String name = stressCase.name();
                String input = stressCase.input();
                RunResponse standardRun = run(draft.standardSolutionLanguage(), draft.standardSolutionCode(),
                        standardCompile.fileId(), input, timeLimitMillis, memoryLimitKb, 1024 * 1024);
                if (!"ACCEPTED".equals(standardRun.status())) {
                    errors.add(error("REFERENCE_RUNTIME_FAILED",
                            "standardSolutionCode failed during reference check on " + name + ": "
                                    + firstText(standardRun.message(), standardRun.stderr()),
                            "standardSolutionCode"));
                    break;
                }
                RunResponse referenceRun = run(firstText(draft.referenceSolutionLanguage(), draft.standardSolutionLanguage()),
                        draft.referenceSolutionCode(), referenceCompile.fileId(), input, timeLimitMillis, memoryLimitKb,
                        1024 * 1024);
                if (!"ACCEPTED".equals(referenceRun.status())) {
                    errors.add(error("REFERENCE_RUNTIME_FAILED",
                            "referenceSolutionCode failed during reference check on " + name + ": "
                                    + firstText(referenceRun.message(), referenceRun.stderr()),
                            "referenceSolutionCode"));
                    break;
                }
                if (!sameOutput(standardRun.stdout(), referenceRun.stdout())) {
                    mismatches.add(new CrossCheckMismatch(name, preview(input), preview(standardRun.stdout()),
                            preview(referenceRun.stdout())));
                    errors.add(error("REFERENCE_MISMATCH",
                            "reference check mismatch on " + name + ": standard <" + preview(standardRun.stdout())
                                    + "> but reference <" + preview(referenceRun.stdout()) + ">",
                            "standardSolutionCode"));
                    break;
                }
            }
        } finally {
            deleteCompiledFile(referenceCompile);
        }
        if (errors.isEmpty() && mismatches.isEmpty()) {
            return CrossCheckReport.passed(stressCases.size());
        }
        return CrossCheckReport.failed(stressCases.size(), mismatches, errors, warnings);
    }

    private void deleteCompiledFile(CompileResponse compile) {
        if (compile == null || !StringUtils.hasText(compile.fileId())) {
            return;
        }
        try {
            post("/api/v1/internal/sandbox/delete-file", new DeleteFileRequest(compile.fileId()), DELETE_FILE_TYPE);
        } catch (RestClientException | IllegalStateException ignored) {
            // Best-effort cleanup must not replace the actual verification outcome.
        }
    }

    private <T> T post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        ApiResponse<T> response = restClient.post()
                .uri(path)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .retrieve()
                .body(type);
        if (response == null || response.code() != 0 || response.data() == null) {
            throw new IllegalStateException(response == null ? "empty internal sandbox response" : response.message());
        }
        return response.data();
    }

    private Map<String, HiddenCase> hiddenCases(Map<String, String> files) {
        Map<String, HiddenCaseParts> parts = new LinkedHashMap<>();
        if (files == null) {
            return Map.of();
        }
        files.forEach((name, content) -> {
            if (name == null || name.isBlank()) {
                return;
            }
            if (name.endsWith(".in")) {
                String key = name.substring(0, name.length() - 3);
                parts.computeIfAbsent(key, ignored -> new HiddenCaseParts()).input = content;
            } else if (name.endsWith(".out")) {
                String key = name.substring(0, name.length() - 4);
                parts.computeIfAbsent(key, ignored -> new HiddenCaseParts()).expectedOutput = content;
            }
        });
        Map<String, HiddenCase> paired = new LinkedHashMap<>();
        parts.forEach((name, value) -> {
            if (StringUtils.hasText(value.input) && StringUtils.hasText(value.expectedOutput)) {
                paired.put(name, new HiddenCase(name, value.input, value.expectedOutput));
            }
        });
        return paired;
    }

    private Map<String, HiddenCase> officialHiddenCases(Map<String, String> files) {
        Map<String, HiddenCase> all = hiddenCases(files);
        Map<String, HiddenCase> filtered = new LinkedHashMap<>();
        all.forEach((name, value) -> {
            if (!isStressCaseName(name)) {
                filtered.put(name, value);
            }
        });
        return filtered;
    }

    private Map<String, HiddenCase> officialInputCases(Map<String, String> files) {
        Map<String, HiddenCase> all = inputCases(files);
        Map<String, HiddenCase> filtered = new LinkedHashMap<>();
        all.forEach((name, value) -> {
            if (!isStressCaseName(name)) {
                filtered.put(name, value);
            }
        });
        return filtered;
    }

    private Map<String, HiddenCase> inputCases(Map<String, String> files) {
        Map<String, HiddenCase> cases = new LinkedHashMap<>();
        if (files == null) {
            return cases;
        }
        files.forEach((name, content) -> {
            if (name == null || name.isBlank() || !name.endsWith(".in") || !StringUtils.hasText(content)) {
                return;
            }
            String key = name.substring(0, name.length() - 3);
            cases.put(key, new HiddenCase(key, content, null));
        });
        return cases;
    }

    private Map<String, HiddenCase> stressCases(Map<String, String> files) {
        Map<String, HiddenCase> all = hiddenCases(files);
        Map<String, HiddenCase> filtered = new LinkedHashMap<>();
        all.forEach((name, value) -> {
            if (isStressCaseName(name)) {
                filtered.put(name, value);
            }
        });
        return filtered;
    }

    private boolean isStressCaseName(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String simple = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return simple.startsWith("stress_small_");
    }

    private int targetHiddenCaseCount(VerificationOptions options) {
        int requested = options == null || options.targetHiddenCaseCount() == null
                ? DEFAULT_HIDDEN_CASE_COUNT
                : options.targetHiddenCaseCount();
        if (requested <= 0) {
            return DEFAULT_HIDDEN_CASE_COUNT;
        }
        return Math.min(MAX_HIDDEN_CASE_COUNT, requested);
    }

    private int targetStressCaseCount(VerificationOptions options) {
        int requested = options == null || options.targetHiddenCaseCount() == null
                ? DEFAULT_STRESS_CASE_COUNT
                : options.targetHiddenCaseCount();
        if (requested <= 0) {
            return DEFAULT_STRESS_CASE_COUNT;
        }
        return Math.min(MAX_STRESS_CASE_COUNT, Math.max(DEFAULT_HIDDEN_CASE_COUNT, requested));
    }

    private int generatorTimeLimitMillis(ProblemDraftResponse draft, int targetCount) {
        int problemLimit = draft == null || draft.timeLimitMillis() == null || draft.timeLimitMillis() <= 0
                ? 1000
                : draft.timeLimitMillis();
        long multiplier = Math.max(30L, Math.max(1L, targetCount) * 10L);
        long scaled = Math.max(MIN_GENERATOR_TIME_LIMIT_MILLIS, (long) problemLimit * multiplier);
        return (int) Math.min(MAX_GENERATOR_TIME_LIMIT_MILLIS, scaled);
    }

    private int generatorMemoryLimitKb(ProblemDraftResponse draft) {
        int memory = draft == null || draft.memoryLimitKb() == null || draft.memoryLimitKb() <= 0
                ? 262_144
                : draft.memoryLimitKb();
        long doubled = (long) memory * 2L;
        return (int) Math.max(MIN_GENERATOR_MEMORY_LIMIT_KB, Math.min(Integer.MAX_VALUE, doubled));
    }

    private int crossCheckTimeLimitMillis(ProblemDraftResponse draft) {
        int problemLimit = draft == null || draft.timeLimitMillis() == null || draft.timeLimitMillis() <= 0
                ? 1000
                : draft.timeLimitMillis();
        return Math.min(10_000, Math.max(2_000, problemLimit));
    }

    private int crossCheckMemoryLimitKb(ProblemDraftResponse draft) {
        int memory = draft == null || draft.memoryLimitKb() == null || draft.memoryLimitKb() <= 0
                ? 262_144
                : draft.memoryLimitKb();
        return Math.max(262_144, memory);
    }

    private String missingGeneratedPairsMessage(String field, int actualPairs, int expectedPairs, PythonScriptResponse script) {
        StringBuilder message = new StringBuilder(field)
                .append(" generated ")
                .append(actualPairs)
                .append(" paired .in/.out cases, expected at least ")
                .append(expectedPairs);
        if (script != null && (script.generatedInputCount() != null || script.generatedOutputCount() != null)) {
            message.append("; collector saw ")
                    .append(script.generatedInputCount() == null ? 0 : script.generatedInputCount())
                    .append(" .in files and ")
                    .append(script.generatedOutputCount() == null ? 0 : script.generatedOutputCount())
                    .append(" .out files");
        }
        return message.toString();
    }

    private String missingGeneratedInputsMessage(String field, int actualInputs, int expectedInputs, PythonScriptResponse script) {
        StringBuilder message = new StringBuilder(field)
                .append(" generated ")
                .append(actualInputs)
                .append(" official .in cases, expected at least ")
                .append(expectedInputs);
        if (script != null && (script.generatedInputCount() != null || script.generatedOutputCount() != null)) {
            message.append("; collector saw ")
                    .append(script.generatedInputCount() == null ? 0 : script.generatedInputCount())
                    .append(" .in files and ")
                    .append(script.generatedOutputCount() == null ? 0 : script.generatedOutputCount())
                    .append(" .out files");
        }
        if (script != null && StringUtils.hasText(script.scanRoot())) {
            message.append("; scanRoot=").append(script.scanRoot());
        }
        return message.toString();
    }

    private String scriptFailureMessage(String field, PythonScriptResponse script) {
        StringBuilder message = new StringBuilder(field).append(" failed");
        if (script == null) {
            return message.append(": no sandbox response").toString();
        }
        if (StringUtils.hasText(script.status())) {
            message.append(" with status=").append(script.status());
        }
        if (script.exitStatus() != null) {
            message.append(" exitStatus=").append(script.exitStatus());
        }
        String detail = scriptFailureDetail(script);
        if (StringUtils.hasText(detail)) {
            message.append(": ").append(preview(detail, 1200));
        }
        return message.toString();
    }

    private String scriptFailureDetail(PythonScriptResponse script) {
        if (script == null) {
            return "";
        }
        String stderr = script.stderr();
        String message = script.message();
        String stdout = script.stdout();
        if (StringUtils.hasText(stderr) && !isGenericGeneratorFailure(stderr)) {
            return stderr;
        }
        if (StringUtils.hasText(message)) {
            if (StringUtils.hasText(stderr) && !message.contains(stderr)) {
                return message + "; stderr: " + stderr;
            }
            return message;
        }
        if (StringUtils.hasText(stderr)) {
            return stderr;
        }
        return StringUtils.hasText(stdout) ? stdout : "";
    }

    private boolean isGenericGeneratorFailure(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "generator.py failed".equals(normalized)
                || "script failed".equals(normalized)
                || normalized.matches(".*\\bgenerator\\.py\\s+failed\\b.*");
    }

    private int stdoutLimit(String expectedOutput) {
        int expectedBytes = expectedOutput == null ? 0 : expectedOutput.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(65536, expectedBytes + 65536);
    }

    private boolean sameOutput(String expected, String actual) {
        return normalizeOutput(expected).equals(normalizeOutput(actual));
    }

    private boolean isTimeLimitExceeded(RunResponse run) {
        String status = run == null ? "" : String.valueOf(run.status());
        String message = run == null ? "" : firstText(run.message(), run.stderr());
        return isTimeLimitExceeded(status, message);
    }

    private boolean isTimeLimitExceeded(String status, String message) {
        String normalizedStatus = status == null ? "" : status.toUpperCase(Locale.ROOT);
        String normalizedMessage = message == null ? "" : message.toUpperCase(Locale.ROOT);
        return normalizedStatus.contains("TIME_LIMIT")
                || normalizedStatus.contains("TLE")
                || normalizedMessage.contains("TIME LIMIT");
    }

    private String claimedComplexity(String generationPlan) {
        if (!StringUtils.hasText(generationPlan)) {
            return null;
        }
        Matcher matcher = COMPLEXITY_PATTERN.matcher(generationPlan);
        return matcher.find() ? matcher.group().replaceAll("\\s+", "") : null;
    }

    private String inferredComplexity(String sourceCode) {
        if (!StringUtils.hasText(sourceCode)) {
            return "UNKNOWN";
        }
        String normalized = sourceCode.replace("\r\n", "\n");
        int maxForDepth = maxNestedForDepth(normalized);
        if (maxForDepth >= 3) {
            return "O(n^3)";
        }
        if (maxForDepth >= 2) {
            return "O(n^2)";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (containsAny(lower,
                "sort(",
                "stable_sort(",
                "priority_queue",
                "lower_bound",
                "upper_bound",
                "binary_search",
                "map<",
                "set<",
                "multiset",
                "fenwick",
                "lowbit",
                "segment",
                "segtree",
                "tree.query",
                "tree.update",
                "bit.query",
                "bit.update")) {
            return "O(n log n)";
        }
        if (containsAny(lower,
                "prefix",
                "partial_sum",
                "pref[",
                "pre[",
                "psum",
                "while (q--",
                "while(q--",
                "for (int qi",
                "for(int qi")) {
            return "O(n+q)";
        }
        return "UNKNOWN";
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private int maxNestedForDepth(String sourceCode) {
        String[] lines = sourceCode == null ? new String[0] : sourceCode.split("\\n");
        int maxDepth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = stripLineComment(lines[i]).trim();
            if (!line.matches(".*\\bfor\\s*\\(.*")) {
                continue;
            }
            int indent = leadingWhitespace(lines[i]);
            int depth = 1;
            for (int j = i + 1; j < lines.length; j++) {
                String nestedLine = stripLineComment(lines[j]).trim();
                if (nestedLine.isEmpty()) {
                    continue;
                }
                int nestedIndent = leadingWhitespace(lines[j]);
                if (nestedIndent <= indent && nestedLine.startsWith("}")) {
                    break;
                }
                if (nestedIndent > indent && nestedLine.matches(".*\\bfor\\s*\\(.*")) {
                    depth++;
                }
            }
            maxDepth = Math.max(maxDepth, depth);
        }
        return maxDepth;
    }

    private String stripLineComment(String value) {
        int index = value == null ? -1 : value.indexOf("//");
        return index >= 0 ? value.substring(0, index) : value == null ? "" : value;
    }

    private int leadingWhitespace(String value) {
        if (value == null) {
            return 0;
        }
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(count))) {
            count++;
        }
        return count;
    }

    private boolean hasLargeScaleText(String value) {
        return StringUtils.hasText(value) && BIG_NUMBER_PATTERN.matcher(value).find();
    }

    private boolean isNaiveHighComplexity(String complexity) {
        if (!StringUtils.hasText(complexity)) {
            return false;
        }
        String normalized = complexity.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return normalized.contains("n^2")
                || normalized.contains("n2")
                || normalized.contains("n*n")
                || normalized.contains("n^3")
                || normalized.contains("2^n")
                || normalized.contains("n!");
    }

    private boolean hasUnboundedBulkOutputRequirement(ProblemDraftResponse draft) {
        if (draft == null) {
            return false;
        }
        String text = firstText(draft.statement(), "") + "\n" + firstText(draft.generationPlan(), "") + "\n" + firstText(draft.notes(), "");
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean bulkOutput = (normalized.contains("输出") && (normalized.contains("整个区间")
                || normalized.contains("完整区间")
                || normalized.contains("排序后的区间")
                || normalized.contains("区间所有")
                || normalized.contains("所有元素")))
                || (normalized.contains("output") && (normalized.contains("entire range")
                || normalized.contains("whole range")
                || normalized.contains("sorted range")
                || normalized.contains("all elements")
                || normalized.contains("full range")));
        if (!bulkOutput) {
            return false;
        }
        return !(normalized.contains("总输出")
                || normalized.contains("输出总量")
                || normalized.contains("输出规模")
                || normalized.contains("输出长度")
                || normalized.contains("total output")
                || normalized.contains("output size")
                || normalized.contains("sum of output"));
    }

    private String standardMaterializationErrorCode(RunResponse run) {
        String status = run == null ? "" : String.valueOf(run.status()).toUpperCase(Locale.ROOT);
        String message = run == null ? "" : firstText(run.message(), run.stderr()).toUpperCase(Locale.ROOT);
        if (isTimeLimitExceeded(run)) {
            return "STANDARD_TLE_ON_GENERATED_CASE";
        }
        if (status.contains("OUTPUT") || message.contains("OUTPUT LIMIT")) {
            return "STANDARD_OUTPUT_MATERIALIZATION_FAILED";
        }
        return "STANDARD_RUNTIME_ON_GENERATED_CASE";
    }

    private String normalizeOutput(String value) {
        return (value == null ? "" : value)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+\\n", "\n")
                .stripTrailing();
    }

    private VerificationReport report(List<VerificationError> errors, List<VerificationWarning> warnings) {
        return new VerificationReport(errors.isEmpty() ? "EXECUTION_VERIFIED" : "FAILED", errors, warnings);
    }

    private VerificationError error(String code, String message, String field) {
        return new VerificationError(code, message, field);
    }

    private String defaultLanguage(String language) {
        return language == null || language.isBlank() ? "cpp" : language.trim().toLowerCase();
    }

    private String preview(String value) {
        return preview(value, 200);
    }

    private String preview(String value, int maxLength) {
        String normalized = normalizeOutput(value);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8203";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private record HiddenCase(String name, String input, String expectedOutput) {
    }

    private String officialPackageFailureMessage(String field, OfficialTestcasePackageReport report,
                                                 PythonScriptResponse script) {
        StringBuilder message = new StringBuilder(field).append(" failed");
        if (report == null) {
            return message.append(": no official package metadata").toString();
        }
        if (StringUtils.hasText(report.status())) {
            message.append(" with status=").append(report.status());
        }
        if (StringUtils.hasText(report.errorCode())) {
            message.append(" code=").append(report.errorCode());
        }
        if (StringUtils.hasText(report.errorMessage())) {
            message.append(": ").append(preview(report.errorMessage(), 1200));
        }
        String scriptDetail = scriptFailureDetail(script);
        if (StringUtils.hasText(scriptDetail)
                && !normalizeOutput(message.toString()).contains(normalizeOutput(scriptDetail))) {
            message.append("; sandbox detail: ").append(preview(scriptDetail, 1200));
        }
        if (script != null && script.exitStatus() != null
                && !message.toString().contains("exitStatus=" + script.exitStatus())) {
            message.append("; exitStatus=").append(script.exitStatus());
        }
        return message.toString();
    }

    private String officialPackageErrorField(String code) {
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("STANDARD_")) {
            return "standardSolutionCode";
        }
        return "testcaseGeneratorPython";
    }

    private record HiddenCaseVerificationResult(List<ComplexityBenchmarkRun> benchmarkRuns, int generatedInputCount,
                                                OfficialTestcasePackageReport officialPackageReport) {
        HiddenCaseVerificationResult {
            benchmarkRuns = benchmarkRuns == null ? List.of() : List.copyOf(benchmarkRuns);
        }

        private static HiddenCaseVerificationResult empty() {
            return new HiddenCaseVerificationResult(List.of(), 0, null);
        }
    }

    private static class HiddenCaseParts {
        private String input;
        private String expectedOutput;
    }

    private record CompileRequest(String language, String sourceCode, Integer timeLimitMillis, Integer memoryLimitKb) {
    }

    private record CompileResponse(String status, String message, String fileId, String stderr, Long timeMillis,
                                   Long memoryKb, Integer exitStatus, Long runTimeMillis) {
    }

    private record DeleteFileRequest(String fileId) {
    }

    private record RunRequest(String language, String sourceCode, String compiledFileId, String stdin,
                              Integer timeLimitMillis, Integer memoryLimitKb, Integer stdoutLimitBytes) {
    }

    private record RunResponse(String status, String message, String stdout, String stderr, Long timeMillis,
                               Long memoryKb, Integer exitStatus, Long runTimeMillis) {
    }

    private record PythonScriptRequest(String script, Integer timeLimitMillis, Integer memoryLimitKb,
                                       Integer targetCaseCount, String collectMode,
                                       String standardSolutionLanguage, String standardSolutionCode,
                                       Integer standardTimeLimitMillis, Integer standardMemoryLimitKb,
                                       Long maxCaseBytes, Long maxPackageBytes) {
        private PythonScriptRequest(String script, Integer timeLimitMillis, Integer memoryLimitKb,
                                    Integer targetCaseCount, String collectMode) {
            this(script, timeLimitMillis, memoryLimitKb, targetCaseCount, collectMode,
                    null, null, null, null, null, null);
        }
    }

    private record PythonScriptResponse(String status, String message, String stdout, String stderr, Long timeMillis,
                                        Long memoryKb, Integer exitStatus, Long runTimeMillis,
                                        Map<String, String> generatedFiles, Integer generatedPairCount,
                                        Integer generatedFileCount, Integer generatedInputCount,
                                        Integer generatedOutputCount,
                                        Map<String, String> crossCheckInputs, String manifestJson, String scanRoot,
                                        OfficialTestcasePackageReport officialPackage) {
    }
}
