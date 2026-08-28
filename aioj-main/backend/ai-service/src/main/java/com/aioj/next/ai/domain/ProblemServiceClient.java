package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiProblemContextRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.aioj.next.contract.ai.ContestParticipantProfile;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.ai.ProblemTitleInfo;
import com.aioj.next.contract.contest.ContestRunWindow;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemCreateRequest;
import com.aioj.next.contract.problem.ProblemUpdateRequest;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TestCaseDto;
import com.aioj.next.contract.problem.TestcasePackageResponse;
import com.aioj.next.contract.problem.TestcaseUploadCompleteRequest;
import com.aioj.next.contract.problem.TestcaseUploadInitRequest;
import com.aioj.next.contract.problem.TestcaseUploadInitResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ProblemServiceClient {
    private static final Logger log = LoggerFactory.getLogger(ProblemServiceClient.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final int TESTCASE_UPLOAD_CHUNK_SIZE_BYTES = 4 * 1024 * 1024;
    private static final int GUARD_CACHE_MAX_ENTRIES = 512;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final InternalApiProperties internalApiProperties;
    private final OperationAuditWriter auditWriter;
    private final long guardCacheTtlMs;
    private final ConcurrentMap<Long, CachedGuardValue<List<RunningContestParticipation>>> runningParticipationsCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CachedGuardValue<List<RunningContestProblemStatement>>> runningStatementsCache = new ConcurrentHashMap<>();

    public ProblemServiceClient(AiProperties properties, ObjectMapper objectMapper,
                                InternalApiProperties internalApiProperties) {
        this(properties, objectMapper, internalApiProperties, null);
    }

    @Autowired
    public ProblemServiceClient(AiProperties properties, ObjectMapper objectMapper,
                                InternalApiProperties internalApiProperties, OperationAuditWriter auditWriter) {
        this(objectMapper,
                internalApiProperties,
                auditWriter,
                properties.getProblemServiceGuardCacheTtlMs(),
                RestClient.builder()
                        .baseUrl(stripTrailingSlash(properties.getProblemServiceUri()))
                        .requestFactory(guardRequestFactory(properties))
                        .build());
    }

    ProblemServiceClient(ObjectMapper objectMapper, InternalApiProperties internalApiProperties,
                         OperationAuditWriter auditWriter, long guardCacheTtlMs, RestClient restClient) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.internalApiProperties = internalApiProperties;
        this.auditWriter = auditWriter;
        this.guardCacheTtlMs = Math.max(0L, guardCacheTtlMs);
    }

    private static SimpleClientHttpRequestFactory guardRequestFactory(AiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(500, properties.getProblemServiceConnectTimeoutMs())));
        factory.setReadTimeout(Duration.ofMillis(Math.max(500, properties.getProblemServiceReadTimeoutMs())));
        return factory;
    }

    public AiProblemContextResponse aiProblemContext(AiProblemContextRequest request) {
        try {
            String response = restClient.post()
                    .uri("/api/v1/internal/ai/problems/context")
                    .header(INTERNAL_TOKEN_HEADER, internalApiProperties.getApiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return parseApiData(response, AiProblemContextResponse.class, "AI problem context response could not be parsed");
        } catch (RestClientResponseException ex) {
            throw problemServiceException("AI problem context failed", false, ex);
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI problem context failed: " + ex.getMessage());
        }
    }

    public AiSubmissionContextResponse aiSubmissionContext(AiSubmissionContextRequest request) {
        try {
            String response = restClient.post()
                    .uri("/api/v1/internal/ai/submissions/context")
                    .header(INTERNAL_TOKEN_HEADER, internalApiProperties.getApiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return parseApiData(response, AiSubmissionContextResponse.class, "AI submission context response could not be parsed");
        } catch (RestClientResponseException ex) {
            throw problemServiceException("AI submission context failed", false, ex);
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI submission context failed: " + ex.getMessage());
        }
    }

    /**
     * Best-effort fetch of statements (with visibility) of problems in the user's RUNNING
     * participations. Per-user short-TTL cache: failures serve the last cached value, and
     * only a total miss (no cache) degrades to an empty list with an audit and error log.
     */
    public List<RunningContestProblemStatement> runningContestProblemStatements(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return guardCached(runningStatementsCache, userId,
                "/api/v1/internal/ai/users/{userId}/running-contest-problem-statements",
                () -> fetchList("/api/v1/internal/ai/users/{userId}/running-contest-problem-statements",
                        RunningContestProblemStatement.class, userId));
    }

    /**
     * Fail-closed variant of {@link #runningContestProblemStatements(Long)} for the V3
     * policy layer (frozen Q5, P3-6): same cache and stale-on-failure serving, but a
     * total miss throws SERVICE_UNAVAILABLE instead of degrading to empty.
     */
    public List<RunningContestProblemStatement> runningContestProblemStatementsStrict(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return guardCached(runningStatementsCache, userId,
                "/api/v1/internal/ai/users/{userId}/running-contest-problem-statements",
                () -> fetchList("/api/v1/internal/ai/users/{userId}/running-contest-problem-statements",
                        RunningContestProblemStatement.class, userId), true);
    }

    /** Best-effort fetch of a contest's run windows; empty means the caller should skip window filtering. */
    public List<ContestRunWindow> contestRunWindows(Long contestId) {
        if (contestId == null) {
            return List.of();
        }
        try {
            String response = restClient.get()
                    .uri("/api/v1/internal/ai/contests/{contestId}/run-windows", contestId)
                    .header(INTERNAL_TOKEN_HEADER, internalApiProperties.getApiToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            int code = root.has("code") ? root.get("code").asInt() : 0;
            if (code != 0) {
                return List.of();
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data == null || data.isNull() || !data.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(data, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ContestRunWindow.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * Uncached variant for the P3-6 time-race recheck (design doc §5.5): the second
     * check must observe participation at answer-return time — the short-TTL guard
     * cache could otherwise replay the turn-start value and hide a contest that
     * started or ended mid-generation. Failures propagate to the caller (the V3
     * policy layer is fail-closed on them); nothing is written back to the cache.
     */
    public List<RunningContestParticipation> runningParticipationsFresh(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            return fetchList("/api/v1/internal/ai/users/{userId}/running-participations",
                    RunningContestParticipation.class, userId);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "Running contest participations unavailable: " + ex.getMessage());
        }
    }

    /**
     * Uncached variant of {@link #runningContestProblemStatements(Long)} for the P3-6
     * time-race recheck; same contract as {@link #runningParticipationsFresh(Long)}.
     */
    public List<RunningContestProblemStatement> runningContestProblemStatementsFresh(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            return fetchList("/api/v1/internal/ai/users/{userId}/running-contest-problem-statements",
                    RunningContestProblemStatement.class, userId);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "Running contest problem statements unavailable: " + ex.getMessage());
        }
    }

    /** Best-effort fetch of RUNNING runs in which the user is an ACTIVE participant (cached like statements). */
    public List<RunningContestParticipation> runningParticipations(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return guardCached(runningParticipationsCache, userId,
                "/api/v1/internal/ai/users/{userId}/running-participations",
                () -> fetchList("/api/v1/internal/ai/users/{userId}/running-participations",
                        RunningContestParticipation.class, userId));
    }

    /**
     * Fail-closed variant of {@link #runningParticipations(Long)} for the V3 policy
     * layer (frozen Q5, P3-6): same cache and stale-on-failure serving, but a total
     * miss throws SERVICE_UNAVAILABLE instead of degrading to empty.
     */
    public List<RunningContestParticipation> runningParticipationsStrict(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return guardCached(runningParticipationsCache, userId,
                "/api/v1/internal/ai/users/{userId}/running-participations",
                () -> fetchList("/api/v1/internal/ai/users/{userId}/running-participations",
                        RunningContestParticipation.class, userId), true);
    }

    public List<ContestParticipantProfile> contestParticipantProfiles(Long contestId) {
        try {
            String response = restClient.get()
                    .uri("/api/v1/internal/ai/contests/{contestId}/participants", contestId)
                    .header(INTERNAL_TOKEN_HEADER, internalApiProperties.getApiToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            int code = root.has("code") ? root.get("code").asInt() : 0;
            if (code != 0) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Contest participant profiles unavailable");
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data == null || data.isNull() || !data.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(data, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ContestParticipantProfile.class));
        } catch (DomainException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw problemServiceException("Contest participant profiles failed", false, ex);
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Contest participant profiles failed: " + ex.getMessage());
        }
    }

    /**
     * Best-effort title lookup for admin usage records; includes private problems because
     * staff review must show which problem a conversation was about.
     */
    public List<ProblemTitleInfo> problemTitles(List<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) {
            return List.of();
        }
        try {
            StringBuilder query = new StringBuilder("/api/v1/internal/ai/problems/titles?ids=");
            query.append(problemIds.stream().distinct().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
            String response = restClient.get()
                    .uri(query.toString())
                    .header(INTERNAL_TOKEN_HEADER, internalApiProperties.getApiToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            int code = root.has("code") ? root.get("code").asInt() : 0;
            if (code != 0) {
                return List.of();
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data == null || data.isNull() || !data.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(data, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ProblemTitleInfo.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    public Long createProblem(ProblemDraftResponse draft, String authorization, ProblemVisibility visibility) {
        if (authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authorization Bearer token is required to import draft");
        }
        List<TestCaseDto> testCases = validateTestCases(draft);
        ProblemCreateRequest request = new ProblemCreateRequest(
                draft.title(),
                parseDifficulty(draft.difficulty()),
                draft.statement(),
                draft.tags() == null ? List.of() : draft.tags(),
                testCases,
                limitOrDefault(draft.timeLimitMillis(), 1000),
                limitOrDefault(draft.memoryLimitKb(), 262144),
                draft.notes(),
                draft.standardSolutionLanguage(),
                draft.standardSolutionCode(),
                null,
                null,
                draft.testcaseGeneratorPython(),
                visibility
        );

        try {
            String response = restClient.post()
                    .uri("/problems")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return parseProblemId(response, false);
        } catch (RestClientResponseException ex) {
            throw problemServiceException("Problem import failed", false, ex);
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem import failed: " + ex.getMessage());
        }
    }

    public Long updateProblem(Long problemId, ProblemDraftResponse draft, String authorization, ProblemVisibility visibility) {
        if (problemId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Imported problem id is required to update draft");
        }
        if (authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authorization Bearer token is required to update imported draft");
        }
        List<TestCaseDto> testCases = validateTestCases(draft);
        ProblemUpdateRequest request = new ProblemUpdateRequest(
                draft.title(),
                parseDifficulty(draft.difficulty()),
                draft.statement(),
                draft.tags() == null ? List.of() : draft.tags(),
                testCases,
                limitOrDefault(draft.timeLimitMillis(), 1000),
                limitOrDefault(draft.memoryLimitKb(), 262144),
                draft.notes(),
                draft.standardSolutionLanguage(),
                draft.standardSolutionCode(),
                null,
                null,
                draft.testcaseGeneratorPython(),
                visibility
        );

        try {
            String response = restClient.put()
                    .uri("/problems/{id}", problemId)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return parseProblemId(response, true);
        } catch (RestClientResponseException ex) {
            throw problemServiceException("Problem update failed", true, ex);
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem update failed: " + ex.getMessage());
        }
    }

    public Long uploadAndActivateTestcasePackage(Long problemId, ProblemDraftTestcaseArtifactEntity artifact,
                                                 Path packagePath, String authorization) {
        if (problemId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem id is required to upload testcase package");
        }
        if (artifact == null || packagePath == null || !Files.isRegularFile(packagePath)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Verified testcase package artifact is missing. Please rerun draft verification.");
        }
        if (authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authorization Bearer token is required to upload testcase package");
        }
        try {
            long size = Files.size(packagePath);
            String sha256 = StringUtils.hasText(artifact.getSha256()) ? artifact.getSha256() : sha256(packagePath);
            int totalChunks = (int) Math.max(1L, (size + TESTCASE_UPLOAD_CHUNK_SIZE_BYTES - 1L) / TESTCASE_UPLOAD_CHUNK_SIZE_BYTES);
            TestcaseUploadInitResponse init = postProblemService(
                    "/problems/{problemId}/testcase-packages/init",
                    authorization,
                    new TestcaseUploadInitRequest(
                            artifact.getFileName(),
                            size,
                            sha256,
                            TESTCASE_UPLOAD_CHUNK_SIZE_BYTES,
                            totalChunks
                    ),
                    TestcaseUploadInitResponse.class,
                    "Testcase package upload init failed",
                    problemId
            );
            if (init == null || init.uploadId() == null || init.uploadId().isBlank()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem-service did not return testcase upload id");
            }
            if (init.packageId() != null && "READY".equals(String.valueOf(init.status()))) {
                TestcasePackageResponse activated = postProblemService(
                        "/problems/{problemId}/testcase-packages/{packageId}/activate",
                        authorization,
                        null,
                        TestcasePackageResponse.class,
                        "Testcase package activation failed",
                        problemId,
                        init.packageId()
                );
                return activated == null ? init.packageId() : activated.id();
            }
            List<Integer> uploadedChunks = uploadChunks(problemId, init.uploadId(), packagePath, size, authorization);
            TestcasePackageResponse completed = postProblemService(
                    "/problems/{problemId}/testcase-packages/uploads/{uploadId}/complete",
                    authorization,
                    new TestcaseUploadCompleteRequest(uploadedChunks, sha256),
                    TestcasePackageResponse.class,
                    "Testcase package upload complete failed",
                    problemId,
                    init.uploadId()
            );
            if (completed == null || completed.id() == null) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem-service did not return testcase package id");
            }
            TestcasePackageResponse activated = postProblemService(
                    "/problems/{problemId}/testcase-packages/{packageId}/activate",
                    authorization,
                    null,
                    TestcasePackageResponse.class,
                    "Testcase package activation failed",
                    problemId,
                    completed.id()
            );
            return activated == null ? completed.id() : activated.id();
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Testcase package upload failed: " + ex.getMessage());
        }
    }

    private List<TestCaseDto> validateTestCases(ProblemDraftResponse draft) {
        List<TestCaseDto> testCases = draft.testCases() == null ? List.of() : draft.testCases();
        if (testCases.isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft has no test cases to import");
        }
        for (int i = 0; i < testCases.size(); i++) {
            TestCaseDto testCase = testCases.get(i);
            if (testCase.input() == null || testCase.input().isBlank()) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft testCases[" + i + "].input is blank");
            }
            if (testCase.expectedOutput() == null || testCase.expectedOutput().isBlank()) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft testCases[" + i + "].expectedOutput is blank");
            }
        }
        boolean hasSample = testCases.stream().anyMatch(TestCaseDto::sample);
        if (!hasSample) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft must include at least one sample test case");
        }
        return testCases;
    }

    private Long parseProblemId(String response, boolean updatingImportedProblem) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int code = root.has("code") ? root.get("code").asInt() : 0;
            if (code != 0) {
                String message = root.has("message") ? root.get("message").asText() : "problem-service rejected request";
                if (updatingImportedProblem && isProblemNotFound(code, message)) {
                    throw new DomainException(ErrorCode.NOT_FOUND, "Imported problem has been deleted");
                }
                throw new DomainException(resolveProblemServiceCode(code), message);
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            JsonNode id = data == null ? null : data.get("id");
            if (id == null || !id.canConvertToLong()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem import response did not include problem id");
            }
            return id.asLong();
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem import response could not be parsed");
        }
    }

    private <T> List<T> fetchList(String uriTemplate, Class<T> elementType, Object... uriVariables) throws Exception {
        String response = restClient.get()
                .uri(uriTemplate, uriVariables)
                .header(INTERNAL_TOKEN_HEADER, internalApiProperties.getApiToken())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        JsonNode root = objectMapper.readTree(response);
        int code = root.has("code") ? root.get("code").asInt() : 0;
        if (code != 0) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "problem-service returned code " + code + " for " + uriTemplate);
        }
        JsonNode data = root.has("data") ? root.get("data") : root;
        if (data == null || data.isNull() || !data.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(data, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, elementType));
    }

    private <T> List<T> guardCached(ConcurrentMap<Long, CachedGuardValue<List<T>>> cache, Long userId,
                                    String endpoint, GuardFetch<List<T>> fetch) {
        return guardCached(cache, userId, endpoint, fetch, false);
    }

    /**
     * Strict (fail-closed) variant for the V3 policy layer (frozen Q5, P3-6): same
     * short-TTL cache and stale-on-failure serving as the lenient path, but a total
     * miss (no cache) throws instead of degrading to empty - an unverifiable contest
     * user must never silently look unrestricted. The degraded audit still records.
     */
    private <T> List<T> guardCached(ConcurrentMap<Long, CachedGuardValue<List<T>>> cache, Long userId,
                                    String endpoint, GuardFetch<List<T>> fetch, boolean strict) {
        long now = System.currentTimeMillis();
        CachedGuardValue<List<T>> cached = cache.get(userId);
        if (cached != null && now < cached.expiresAtMs()) {
            return cached.value();
        }
        try {
            List<T> value = fetch.get();
            if (cache.size() >= GUARD_CACHE_MAX_ENTRIES) {
                cache.clear();
            }
            cache.put(userId, new CachedGuardValue<>(value, now + guardCacheTtlMs));
            return value;
        } catch (Exception ex) {
            if (cached != null) {
                log.warn("Contest guard fetch failed; serving cached value endpoint={} user={} error={}",
                        endpoint, userId, ex.toString());
                return cached.value();
            }
            log.error("Contest guard fetch failed with no cache; {} endpoint={} user={} error={}",
                    strict ? "failing closed" : "degrading to empty", endpoint, userId, ex.toString());
            recordGuardDegraded(endpoint, ex, userId);
            if (strict) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                        "Contest guard data unavailable (fail-closed): " + endpoint);
            }
            return List.of();
        }
    }

    private void recordGuardDegraded(String endpoint, Exception ex, Long userId) {
        if (auditWriter == null) {
            return;
        }
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("endpoint", endpoint);
            String error = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage());
            summary.put("error", error.length() <= 300 ? error : error.substring(0, 300));
            auditWriter.record(
                    "AI_CONTEST_GUARD_DEGRADED",
                    "CONTEST_AI_POLICY",
                    null,
                    "DEGRADED",
                    summary,
                    userId,
                    null,
                    null,
                    userId
            );
        } catch (RuntimeException auditFailure) {
            log.warn("Contest guard degraded audit failed endpoint={} user={} error={}",
                    endpoint, userId, auditFailure.toString());
        }
    }

    @FunctionalInterface
    private interface GuardFetch<T> {
        T get() throws Exception;
    }

    private record CachedGuardValue<T>(T value, long expiresAtMs) {
    }

    private <T> T parseApiData(String response, Class<T> type, String parseErrorMessage) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int code = root.has("code") ? root.get("code").asInt() : 0;
            if (code != 0) {
                String message = root.has("message") ? root.get("message").asText() : "problem-service rejected request";
                throw new DomainException(resolveProblemServiceCode(code), message);
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data == null || data.isNull()) {
                return null;
            }
            return objectMapper.treeToValue(data, type);
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, parseErrorMessage);
        }
    }

    private <T> T postProblemService(String uriTemplate, String authorization, Object body, Class<T> type,
                                     String operation, Object... uriVariables) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(uriTemplate, uriVariables)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .accept(MediaType.APPLICATION_JSON);
            RestClient.RequestHeadersSpec<?> requestSpec = spec;
            if (body != null) {
                requestSpec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            String response = requestSpec.retrieve().body(String.class);
            return parseApiData(response, type, operation + " response could not be parsed");
        } catch (RestClientResponseException ex) {
            throw problemServiceException(operation, false, ex);
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, operation + ": " + ex.getMessage());
        }
    }

    private List<Integer> uploadChunks(Long problemId, String uploadId, Path packagePath, long size,
                                       String authorization) throws Exception {
        List<Integer> uploaded = new ArrayList<>();
        try (InputStream input = Files.newInputStream(packagePath)) {
            byte[] buffer = new byte[TESTCASE_UPLOAD_CHUNK_SIZE_BYTES];
            int index = 0;
            long remaining = size;
            while (remaining > 0) {
                int expected = (int) Math.min(buffer.length, remaining);
                int read = input.readNBytes(buffer, 0, expected);
                if (read <= 0) {
                    throw new DomainException(ErrorCode.INTERNAL_ERROR, "Unexpected end of testcase package while uploading");
                }
                byte[] chunk = java.util.Arrays.copyOf(buffer, read);
                String chunkSha = sha256(chunk);
                putChunk(problemId, uploadId, index, chunk, chunkSha, authorization);
                uploaded.add(index);
                index++;
                remaining -= read;
            }
        }
        return uploaded;
    }

    private void putChunk(Long problemId, String uploadId, int index, byte[] chunk, String chunkSha,
                          String authorization) {
        try {
            String response = restClient.put()
                    .uri("/problems/{problemId}/testcase-packages/uploads/{uploadId}/chunks/{index}",
                            problemId, uploadId, index)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .header("X-Chunk-Sha256", chunkSha)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(chunk)
                    .retrieve()
                    .body(String.class);
            parseApiData(response, JsonNode.class, "Testcase package chunk response could not be parsed");
        } catch (RestClientResponseException ex) {
            throw problemServiceException("Testcase package chunk upload failed", false, ex);
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Testcase package chunk upload failed: " + ex.getMessage());
        }
    }

    private DomainException problemServiceException(String operation, boolean updatingImportedProblem, RestClientResponseException ex) {
        ProblemServiceError error = parseProblemServiceError(ex.getResponseBodyAsString());
        if (updatingImportedProblem
                && (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value() || error.isProblemNotFound())) {
            return new DomainException(ErrorCode.NOT_FOUND, "Imported problem has been deleted");
        }
        ErrorCode code = error.code() == null
                ? (ex.getStatusCode().is4xxClientError() ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL_ERROR)
                : resolveProblemServiceCode(error.code());
        return new DomainException(code, operation + ": " + error.summary());
    }

    private ProblemServiceError parseProblemServiceError(String body) {
        if (body == null || body.isBlank()) {
            return new ProblemServiceError(null, "empty response body", "");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            Integer code = root.has("code") && root.get("code").canConvertToInt() ? root.get("code").asInt() : null;
            String message = root.has("message") && !root.get("message").isNull()
                    ? root.get("message").asText()
                    : summarize(body);
            return new ProblemServiceError(code, message, body);
        } catch (Exception ignored) {
            return new ProblemServiceError(null, summarize(body), body);
        }
    }

    private ErrorCode resolveProblemServiceCode(int code) {
        for (ErrorCode value : ErrorCode.values()) {
            if (value.code() == code) {
                return value;
            }
        }
        return code >= 50000 ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST;
    }

    private boolean isProblemNotFound(int code, String message) {
        return code == ErrorCode.NOT_FOUND.code()
                || looksLikeProblemNotFound(message);
    }

    private Difficulty parseDifficulty(String value) {
        try {
            return Difficulty.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported draft difficulty: " + value);
        }
    }

    private int limitOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
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

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "empty response body";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record ProblemServiceError(Integer code, String message, String rawBody) {
        private boolean isProblemNotFound() {
            return (code != null && code == ErrorCode.NOT_FOUND.code())
                    || looksLikeProblemNotFound(message)
                    || looksLikeProblemNotFound(rawBody);
        }

        private String summary() {
            return message == null || message.isBlank() ? rawBody : message;
        }
    }

    private static boolean looksLikeProblemNotFound(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.contains("problem not found")
                || normalized.contains("imported problem has been deleted")
                || normalized.contains("linked problem has been deleted")
                || normalized.contains("题目不存在")
                || normalized.contains("题目已被删除")
                || normalized.contains("题目不存在或已被删除")
                || normalized.contains("对应题目已被删除");
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8202";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
