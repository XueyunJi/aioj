package com.aioj.next.judge.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.domain.SandboxExecutionClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/internal/sandbox")
public class InternalSandboxController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final int SCRIPT_STDOUT_LIMIT_BYTES = 1024 * 1024;
    private static final int DEFAULT_SCRIPT_TARGET_CASE_COUNT = 3;
    private static final int MAX_SCRIPT_TARGET_CASE_COUNT = 20;
    private static final String COLLECT_MODE_OFFICIAL_PACKAGE = "OFFICIAL_PACKAGE";
    private static final long DEFAULT_MAX_OFFICIAL_CASE_BYTES = 8L * 1024L * 1024L;
    private static final long DEFAULT_MAX_OFFICIAL_PACKAGE_BYTES = 50L * 1024L * 1024L;

    private final SandboxExecutionClient sandboxExecutionClient;
    private final JudgeWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public InternalSandboxController(SandboxExecutionClient sandboxExecutionClient,
                                     JudgeWorkerProperties properties,
                                     ObjectMapper objectMapper) {
        this.sandboxExecutionClient = sandboxExecutionClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/compile")
    public ApiResponse<CompileResponse> compile(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody CompileRequest request
    ) {
        requireInternalToken(token);
        String language = normalizeLanguage(request.language());
        SandboxExecutionClient.CompileOutcome outcome = sandboxExecutionClient.compileSource(
                language,
                request.sourceCode(),
                SandboxExecutionClient.millisToNanos(limitOrDefault(request.timeLimitMillis(), 1000L) * 10),
                SandboxExecutionClient.kbToBytes(limitOrDefault(request.memoryLimitKb(), 262144L) * 2)
        );
        return ApiResponse.ok(new CompileResponse(
                outcome.failed() ? "FAILED" : "ACCEPTED",
                outcome.message(),
                outcome.fileId(),
                outcome.stderr(),
                outcome.timeMillis(),
                outcome.memoryKb(),
                outcome.exitStatus(),
                outcome.runTimeMillis()
        ));
    }

    @PostMapping("/run-one")
    public ApiResponse<RunResponse> runOne(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody RunRequest request
    ) {
        requireInternalToken(token);
        return ApiResponse.ok(run(request));
    }

    @PostMapping("/run-batch")
    public ApiResponse<RunBatchResponse> runBatch(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody RunBatchRequest request
    ) {
        requireInternalToken(token);
        List<RunResponse> results = new ArrayList<>();
        for (String stdin : request.inputs() == null ? List.<String>of() : request.inputs()) {
            results.add(run(new RunRequest(
                    normalizeLanguage(request.language()),
                    request.sourceCode(),
                    request.compiledFileId(),
                    stdin,
                    request.timeLimitMillis(),
                    request.memoryLimitKb(),
                    request.stdoutLimitBytes()
            )));
        }
        return ApiResponse.ok(new RunBatchResponse(results));
    }

    @PostMapping("/run-python-script")
    public ApiResponse<PythonScriptResponse> runPythonScript(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody PythonScriptRequest request
    ) {
        requireInternalToken(token);
        int targetCaseCount = targetCaseCount(request.targetCaseCount());
        SandboxExecutionClient.RunOutcome outcome = COLLECT_MODE_OFFICIAL_PACKAGE.equals(request.collectMode())
                ? sandboxExecutionClient.runOfficialPackageScript(
                request.script(),
                normalizeLanguage(request.standardSolutionLanguage()),
                request.standardSolutionCode(),
                SandboxExecutionClient.millisToNanos(limitOrDefault(request.timeLimitMillis(), 30_000L)),
                SandboxExecutionClient.kbToBytes(limitOrDefault(request.memoryLimitKb(), 524_288L)),
                SCRIPT_STDOUT_LIMIT_BYTES,
                targetCaseCount,
                SandboxExecutionClient.millisToNanos(limitOrDefault(request.standardTimeLimitMillis(), 1000L)),
                SandboxExecutionClient.kbToBytes(limitOrDefault(request.standardMemoryLimitKb(), 262_144L)),
                limitOrDefault(request.maxCaseBytes(), DEFAULT_MAX_OFFICIAL_CASE_BYTES),
                limitOrDefault(request.maxPackageBytes(), DEFAULT_MAX_OFFICIAL_PACKAGE_BYTES)
        )
                : sandboxExecutionClient.runPythonScript(
                request.script(),
                SandboxExecutionClient.millisToNanos(limitOrDefault(request.timeLimitMillis(), 30_000L)),
                SandboxExecutionClient.kbToBytes(limitOrDefault(request.memoryLimitKb(), 524_288L)),
                SCRIPT_STDOUT_LIMIT_BYTES,
                targetCaseCount,
                request.collectMode()
        );
        GeneratedFilesPayload generatedFiles = parseGeneratedFiles(outcome.generatedFilesJson(), outcome.stdout());
        OfficialPackagePayload officialPackage = parseOfficialPackage(outcome.officialPackageJson(), outcome.fileIds());
        return ApiResponse.ok(new PythonScriptResponse(
                outcome.accepted() ? "ACCEPTED" : "FAILED",
                outcome.message(),
                outcome.stdout(),
                outcome.stderr(),
                outcome.timeMillis(),
                outcome.memoryKb(),
                outcome.exitStatus(),
                outcome.runTimeMillis(),
                generatedFiles.files(),
                generatedFiles.generatedPairCount(),
                generatedFiles.generatedFileCount(),
                generatedFiles.generatedInputCount(),
                generatedFiles.generatedOutputCount(),
                generatedFiles.crossCheckInputs(),
                generatedFiles.manifestJson(),
                generatedFiles.scanRoot(),
                officialPackage
        ));
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable String fileId
    ) {
        requireInternalToken(token);
        StreamingResponseBody body = outputStream -> sandboxExecutionClient.downloadCachedFile(fileId, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @PostMapping("/delete-file")
    public ApiResponse<Boolean> deleteFile(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody DeleteFileRequest request
    ) {
        requireInternalToken(token);
        sandboxExecutionClient.deleteCachedFile(request.fileId());
        return ApiResponse.ok(Boolean.TRUE);
    }

    private RunResponse run(RunRequest request) {
        String language = normalizeLanguage(request.language());
        SandboxExecutionClient.RunOutcome outcome = sandboxExecutionClient.runSource(
                language,
                request.sourceCode(),
                request.compiledFileId(),
                request.stdin(),
                SandboxExecutionClient.millisToNanos(limitOrDefault(request.timeLimitMillis(), 1000L)),
                SandboxExecutionClient.kbToBytes(limitOrDefault(request.memoryLimitKb(), 262144L)),
                limitOrDefault(request.stdoutLimitBytes(), 65536L).intValue()
        );
        return new RunResponse(
                outcome.accepted() ? "ACCEPTED" : "FAILED",
                outcome.message(),
                outcome.stdout(),
                outcome.stderr(),
                outcome.timeMillis(),
                outcome.memoryKb(),
                outcome.exitStatus(),
                outcome.runTimeMillis()
        );
    }

    private void requireInternalToken(String token) {
        String expected = properties.getInternalApiToken();
        if (expected == null || expected.isBlank() || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal token missing or invalid");
        }
    }

    static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Sandbox language is required");
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "cpp", "c++", "c++17", "cpp17" -> "cpp";
            case "java" -> "java";
            case "python", "py", "python3" -> "python";
            default -> throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported sandbox language: " + language);
        };
    }

    private GeneratedFilesPayload parseGeneratedFiles(String generatedFilesJson, String stdout) {
        if (generatedFilesJson != null && !generatedFilesJson.isBlank()) {
            Optional<GeneratedFilesPayload> parsed = parseGeneratedFilesJson(generatedFilesJson);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        if (stdout == null || stdout.isBlank()) {
            return GeneratedFilesPayload.empty();
        }
        Optional<GeneratedFilesPayload> direct = parseGeneratedFilesJson(stdout);
        if (direct.isPresent()) {
            return direct.get();
        }
        String[] lines = stdout.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index] == null ? "" : lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            Optional<GeneratedFilesPayload> parsed = parseGeneratedFilesJson(line);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        return GeneratedFilesPayload.empty();
    }

    private Optional<GeneratedFilesPayload> parseGeneratedFilesJson(String value) {
        try {
            JsonNode rootNode = objectMapper.readTree(value);
            JsonNode filesNode = rootNode.path("files");
            if (!filesNode.isObject()) {
                return Optional.empty();
            }
            Map<String, String> files = new LinkedHashMap<>();
            filesNode.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    files.put(entry.getKey(), entry.getValue().asText());
                }
            });
            JsonNode generatedPairCountNode = rootNode.path("generatedPairCount");
            Integer generatedPairCount = generatedPairCountNode.canConvertToInt()
                    ? generatedPairCountNode.asInt()
                    : null;
            return Optional.of(new GeneratedFilesPayload(
                    files,
                    textMap(rootNode.path("crossCheckInputs")),
                    generatedPairCount,
                    integerOrNull(rootNode.path("generatedFileCount")),
                    integerOrNull(rootNode.path("generatedInputCount")),
                    integerOrNull(rootNode.path("generatedOutputCount")),
                    textOrNull(rootNode.path("manifestJson")),
                    textOrNull(rootNode.path("scanRoot"))
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private OfficialPackagePayload parseOfficialPackage(String value, Map<String, String> fileIds) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(value);
            String packageFileId = fileIds == null ? null : fileIds.get(".aioj_official_hidden.zip");
            List<OfficialCasePayload> cases = new ArrayList<>();
            JsonNode casesNode = rootNode.path("cases");
            if (casesNode.isArray()) {
                for (JsonNode node : casesNode) {
                    cases.add(new OfficialCasePayload(
                            textOrNull(node.path("name")),
                            textOrNull(node.path("sourceInputPath")),
                            textOrNull(node.path("inputPath")),
                            textOrNull(node.path("outputPath")),
                            longOrNull(node.path("inputBytes")),
                            longOrNull(node.path("outputBytes")),
                            textOrNull(node.path("inputSha256")),
                            textOrNull(node.path("outputSha256")),
                            textOrNull(node.path("status")),
                            longOrNull(node.path("timeMillis")),
                            longOrNull(node.path("memoryKb")),
                            textOrNull(node.path("message"))
                    ));
                }
            }
            return new OfficialPackagePayload(
                    textOrNull(rootNode.path("status")),
                    textOrNull(rootNode.path("errorCode")),
                    textOrNull(rootNode.path("errorMessage")),
                    packageFileId,
                    textOrNull(rootNode.path("packageFileName")),
                    longOrNull(rootNode.path("packageFileSizeBytes")),
                    textOrNull(rootNode.path("packageSha256")),
                    integerOrNull(rootNode.path("caseCount")),
                    integerOrNull(rootNode.path("generatedInputCount")),
                    integerOrNull(rootNode.path("generatedOutputCount")),
                    longOrNull(rootNode.path("totalInputBytes")),
                    longOrNull(rootNode.path("totalOutputBytes")),
                    longOrNull(rootNode.path("totalBytes")),
                    longOrNull(rootNode.path("largestCaseBytes")),
                    textOrNull(rootNode.path("manifestJson")),
                    textOrNull(rootNode.path("scanRoot")),
                    cases
            );
        } catch (Exception ex) {
            return new OfficialPackagePayload("FAILED", "GENERATOR_PACKAGE_METADATA_INVALID",
                    "official testcase package metadata could not be parsed", null, null, null,
                    null, 0, 0, 0, 0L, 0L, 0L, 0L, null, null, List.of());
        }
    }

    private Integer integerOrNull(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.asInt() : null;
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private Long longOrNull(JsonNode node) {
        return node != null && node.canConvertToLong() ? node.asLong() : null;
    }

    private Map<String, String> textMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return values;
    }

    private int targetCaseCount(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_SCRIPT_TARGET_CASE_COUNT;
        }
        return Math.min(MAX_SCRIPT_TARGET_CASE_COUNT, value);
    }

    private Long limitOrDefault(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private Long limitOrDefault(Integer value, long fallback) {
        return value == null || value <= 0 ? fallback : value.longValue();
    }

    public record CompileRequest(String language, String sourceCode, Integer timeLimitMillis, Integer memoryLimitKb) {
    }

    public record CompileResponse(String status, String message, String fileId, String stderr, Long timeMillis,
                                  Long memoryKb, Integer exitStatus, Long runTimeMillis) {
    }

    public record DeleteFileRequest(String fileId) {
    }

    public record RunRequest(String language, String sourceCode, String compiledFileId, String stdin,
                             Integer timeLimitMillis, Integer memoryLimitKb, Integer stdoutLimitBytes) {
    }

    public record RunBatchRequest(String language, String sourceCode, String compiledFileId, List<String> inputs,
                                  Integer timeLimitMillis, Integer memoryLimitKb, Integer stdoutLimitBytes) {
    }

    public record RunResponse(String status, String message, String stdout, String stderr, Long timeMillis,
                              Long memoryKb, Integer exitStatus, Long runTimeMillis) {
    }

    public record RunBatchResponse(List<RunResponse> results) {
    }

    public record PythonScriptRequest(String script, Integer timeLimitMillis, Integer memoryLimitKb,
                                      Integer targetCaseCount, String collectMode,
                                      String standardSolutionLanguage, String standardSolutionCode,
                                      Integer standardTimeLimitMillis, Integer standardMemoryLimitKb,
                                      Long maxCaseBytes, Long maxPackageBytes) {
        public PythonScriptRequest(String script, Integer timeLimitMillis, Integer memoryLimitKb,
                                   Integer targetCaseCount) {
            this(script, timeLimitMillis, memoryLimitKb, targetCaseCount, null,
                    null, null, null, null, null, null);
        }

        public PythonScriptRequest(String script, Integer timeLimitMillis, Integer memoryLimitKb,
                                   Integer targetCaseCount, String collectMode) {
            this(script, timeLimitMillis, memoryLimitKb, targetCaseCount, collectMode,
                    null, null, null, null, null, null);
        }
    }

    public record PythonScriptResponse(String status, String message, String stdout, String stderr, Long timeMillis,
                                       Long memoryKb, Integer exitStatus, Long runTimeMillis,
                                       Map<String, String> generatedFiles, Integer generatedPairCount,
                                       Integer generatedFileCount, Integer generatedInputCount,
                                       Integer generatedOutputCount,
                                       Map<String, String> crossCheckInputs, String manifestJson, String scanRoot,
                                       OfficialPackagePayload officialPackage) {
    }

    public record OfficialPackagePayload(String status, String errorCode, String errorMessage,
                                         String packageFileId, String packageFileName,
                                         Long packageFileSizeBytes, String packageSha256,
                                         Integer caseCount, Integer generatedInputCount,
                                         Integer generatedOutputCount, Long totalInputBytes,
                                         Long totalOutputBytes, Long totalBytes, Long largestCaseBytes,
                                         String manifestJson, String scanRoot,
                                         List<OfficialCasePayload> cases) {
    }

    public record OfficialCasePayload(String name, String sourceInputPath, String inputPath, String outputPath,
                                      Long inputBytes, Long outputBytes, String inputSha256, String outputSha256,
                                      String status, Long timeMillis, Long memoryKb, String message) {
    }

    private record GeneratedFilesPayload(Map<String, String> files, Map<String, String> crossCheckInputs,
                                         Integer generatedPairCount,
                                         Integer generatedFileCount, Integer generatedInputCount,
                                         Integer generatedOutputCount, String manifestJson, String scanRoot) {
        private static GeneratedFilesPayload empty() {
            return new GeneratedFilesPayload(Map.of(), Map.of(), null, 0, 0, 0, null, null);
        }
    }
}
