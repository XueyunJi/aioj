package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.problem.ProblemDraftAuditContext;
import com.aioj.next.ai.domain.problem.VerificationError;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.persistence.entity.ProblemDraftAuditSnapshotEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftAuditSnapshotMapper;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProblemDraftAuditSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(ProblemDraftAuditSnapshotService.class);
    private static final int TEXT_PREVIEW_MAX = 240;
    private static final int ERROR_MESSAGE_MAX = 1000;

    private final ProblemDraftAuditSnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    public ProblemDraftAuditSnapshotService(ProblemDraftAuditSnapshotMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRequirement(ProblemDraftAuditContext context, Long creatorUserId, Long draftId,
                                  ProblemDraftRequest request) {
        record(context, creatorUserId, draftId, "REQUIREMENT", 0, "OK", null, 0, 0,
                Map.of("request", requestSummary(request)), null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPlan(ProblemDraftAuditContext context, Long creatorUserId, Long draftId,
                           ProblemDraftRequest request, ProblemDraftResponse draft) {
        record(context, creatorUserId, draftId, "PLAN", 0, "OK", draft == null ? null : draft.model(),
                draft == null ? 0 : draft.promptTokens(), draft == null ? 0 : draft.completionTokens(),
                Map.of("request", requestSummary(request)),
                Map.of("generationPlan", textSummary(draft == null ? null : draft.generationPlan())), null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDraft(ProblemDraftAuditContext context, Long creatorUserId, Long draftId,
                            ProblemDraftResponse draft) {
        record(context, creatorUserId, draftId, "DRAFT", 0, status(draft == null ? null : draft.validationStatus()),
                draft == null ? null : draft.model(), draft == null ? 0 : draft.promptTokens(),
                draft == null ? 0 : draft.completionTokens(), null,
                Map.of("draft", draftSummary(draft)), null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStressGenerator(ProblemDraftAuditContext context, Long creatorUserId, Long draftId,
                                      ProblemDraftResponse draft, List<VerificationError> errors) {
        String status = errors == null || errors.isEmpty() ? "OK" : "FAILED";
        record(context, creatorUserId, draftId, "STRESS_GENERATOR", 0, status,
                draft == null ? null : draft.model(), draft == null ? 0 : draft.promptTokens(),
                draft == null ? 0 : draft.completionTokens(), null,
                Map.of(
                        "stressTestcaseGeneratorPython", codeSummary(draft == null ? null : draft.stressTestcaseGeneratorPython()),
                        "errors", errorSummaries(errors)
                ),
                firstErrorCode(errors), firstErrorMessage(errors));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVerification(ProblemDraftAuditContext context, Long creatorUserId, Long draftId,
                                   ProblemDraftResponse draft, VerificationReport staticReport,
                                   String verificationStatus, String reportJson) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("staticReport", reportSummary(staticReport));
        output.put("storedReport", storedReportSummary(reportJson));
        record(context, creatorUserId, draftId, "VERIFICATION", 0,
                nonBlank(verificationStatus, "FAILED"),
                draft == null ? null : draft.model(), draft == null ? 0 : draft.promptTokens(),
                draft == null ? 0 : draft.completionTokens(), null, output,
                firstStoredErrorCode(reportJson), firstStoredErrorMessage(reportJson));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRepair(ProblemDraftAuditContext context, Long creatorUserId, Long draftId,
                             int attempt, ProblemDraftResponse draft, String verificationStatus,
                             String reportJson) {
        record(context, creatorUserId, draftId, "REPAIR", Math.max(0, attempt),
                nonBlank(verificationStatus, status(draft == null ? null : draft.verificationStatus())),
                draft == null ? null : draft.model(), draft == null ? 0 : draft.promptTokens(),
                draft == null ? 0 : draft.completionTokens(),
                Map.of("attempt", Math.max(0, attempt)),
                Map.of(
                        "draft", draftSummary(draft),
                        "storedReport", storedReportSummary(reportJson)
                ),
                firstStoredErrorCode(reportJson), firstStoredErrorMessage(reportJson));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(ProblemDraftAuditContext context, Long creatorUserId, Long draftId,
                              String stage, ProblemDraftRequest request, RuntimeException error) {
        record(context, creatorUserId, draftId, nonBlank(stage, "DRAFT"), 0, "FAILED", null, 0, 0,
                Map.of("request", requestSummary(request)), null,
                error == null ? null : error.getClass().getSimpleName(),
                error == null ? null : error.getMessage());
    }

    private void record(ProblemDraftAuditContext context, Long creatorUserId, Long draftId, String stage, int attempt,
                        String status, String model, long promptTokens, long completionTokens,
                        Object inputSummary, Object outputSummary, String errorCode, String errorMessage) {
        if (context == null || !context.hasJob()) {
            return;
        }
        try {
            ProblemDraftAuditSnapshotEntity entity = new ProblemDraftAuditSnapshotEntity();
            entity.setId(IdWorker.getId());
            entity.setJobId(context.jobId());
            entity.setDraftId(draftId);
            entity.setCreatorUserId(creatorUserId);
            entity.setStage(nonBlank(stage, "UNKNOWN"));
            entity.setAttempt(Math.max(0, attempt));
            entity.setStatus(nonBlank(status, "UNKNOWN"));
            entity.setModel(truncate(model, 128));
            entity.setPromptTokens(Math.max(0, promptTokens));
            entity.setCompletionTokens(Math.max(0, completionTokens));
            entity.setInputSummaryJson(toJson(inputSummary));
            entity.setOutputSummaryJson(toJson(outputSummary));
            entity.setErrorCode(truncate(errorCode, 128));
            entity.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX));
            entity.setCreatedAt(LocalDateTime.now());
            mapper.insert(entity);
        } catch (RuntimeException ex) {
            log.warn("Problem draft audit snapshot write failed jobId={} draftId={} stage={} error={}",
                    context.jobId(), draftId, stage, ex.toString());
        }
    }

    private Map<String, Object> requestSummary(ProblemDraftRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("topic", truncate(request.topic(), TEXT_PREVIEW_MAX));
        summary.put("difficulty", request.difficulty());
        summary.put("cfRating", request.cfRating());
        summary.put("algorithm", truncate(request.algorithm(), TEXT_PREVIEW_MAX));
        summary.put("tags", request.tags() == null ? List.of() : request.tags());
        summary.put("targetHiddenCaseCount", request.targetHiddenCaseCount());
        summary.put("enableAutoRepair", Boolean.TRUE.equals(request.enableAutoRepair()));
        summary.put("enableReferenceCheck", Boolean.TRUE.equals(request.enableReferenceCheck()));
        summary.put("inputOutputSpec", textSummary(request.inputOutputSpec()));
        summary.put("dataConstraints", textSummary(request.dataConstraints()));
        summary.put("qualityRequirements", textSummary(request.qualityRequirements()));
        summary.put("solutionRequirement", textSummary(request.solutionRequirement()));
        return summary;
    }

    private Map<String, Object> draftSummary(ProblemDraftResponse draft) {
        if (draft == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("title", truncate(draft.title(), TEXT_PREVIEW_MAX));
        summary.put("difficulty", draft.difficulty());
        summary.put("tags", draft.tags() == null ? List.of() : draft.tags());
        summary.put("validationStatus", draft.validationStatus());
        summary.put("verificationStatus", draft.verificationStatus());
        summary.put("repairAttemptCount", draft.repairAttemptCount());
        summary.put("timeLimitMillis", draft.timeLimitMillis());
        summary.put("memoryLimitKb", draft.memoryLimitKb());
        summary.put("sampleCaseCount", draft.testCases() == null ? 0 : draft.testCases().size());
        summary.put("standardSolutionCode", codeSummary(draft.standardSolutionCode()));
        summary.put("referenceSolutionCode", codeSummary(draft.referenceSolutionCode()));
        summary.put("testcaseGeneratorPython", codeSummary(draft.testcaseGeneratorPython()));
        summary.put("stressTestcaseGeneratorPython", codeSummary(draft.stressTestcaseGeneratorPython()));
        summary.put("generationPlan", textSummary(draft.generationPlan()));
        return summary;
    }

    private Map<String, Object> reportSummary(VerificationReport report) {
        if (report == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", report.status());
        summary.put("errors", errorSummaries(report.errors()));
        summary.put("warnings", report.warnings() == null ? List.of() : report.warnings().stream()
                .limit(12)
                .map(warning -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", nonBlank(warning.code(), "UNKNOWN"));
                    item.put("field", warning.field());
                    item.put("message", truncate(warning.message(), TEXT_PREVIEW_MAX));
                    return item;
                })
                .toList());
        return summary;
    }

    private Map<String, Object> storedReportSummary(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(reportJson);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", text(root, "status"));
            summary.put("staticStatus", text(root.path("staticReport"), "status"));
            summary.put("sandboxStatus", text(root.path("sandboxReport"), "status"));
            summary.put("crosscheckStatus", text(root.path("crosscheckReport"), "status"));
            summary.put("complexityStatus", text(root.path("complexityReport"), "status"));
            JsonNode failure = root.path("failureClassification");
            if (failure.isObject()) {
                Map<String, Object> classification = new LinkedHashMap<>();
                classification.put("category", text(failure, "category"));
                classification.put("repairScope", text(failure, "repairScope"));
                classification.put("confidence", failure.path("confidence").isNumber() ? failure.path("confidence").asDouble() : 0);
                summary.put("failureClassification", classification);
            }
            summary.put("errorCodes", collectErrorCodes(root));
            return summary;
        } catch (Exception ex) {
            return Map.of("parseError", ex.getClass().getSimpleName(), "length", reportJson.length());
        }
    }

    private List<Map<String, Object>> errorSummaries(List<VerificationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        return errors.stream()
                .limit(12)
                .map(error -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", nonBlank(error.code(), "UNKNOWN"));
                    item.put("field", error.field());
                    item.put("message", truncate(error.message(), TEXT_PREVIEW_MAX));
                    return item;
                })
                .toList();
    }

    private List<String> collectErrorCodes(JsonNode root) {
        List<String> sections = List.of("staticReport", "sandboxReport", "crosscheckReport", "complexityReport");
        return sections.stream()
                .flatMap(section -> {
                    JsonNode errors = root.path(section).path("errors");
                    if (!errors.isArray()) {
                        return List.<String>of().stream();
                    }
                    return errors.findValuesAsText("code").stream();
                })
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .limit(20)
                .toList();
    }

    private String firstStoredErrorCode(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return null;
        }
        try {
            List<String> codes = collectErrorCodes(objectMapper.readTree(reportJson));
            return codes.isEmpty() ? null : codes.get(0);
        } catch (Exception ex) {
            return null;
        }
    }

    private String firstStoredErrorMessage(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(reportJson);
            for (String section : List.of("staticReport", "sandboxReport", "crosscheckReport", "complexityReport")) {
                JsonNode errors = root.path(section).path("errors");
                if (errors.isArray() && !errors.isEmpty()) {
                    return truncate(text(errors.get(0), "message"), ERROR_MESSAGE_MAX);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String firstErrorCode(List<VerificationError> errors) {
        return errors == null || errors.isEmpty() ? null : errors.get(0).code();
    }

    private String firstErrorMessage(List<VerificationError> errors) {
        return errors == null || errors.isEmpty() ? null : errors.get(0).message();
    }

    private Map<String, Object> codeSummary(String code) {
        if (code == null || code.isBlank()) {
            return Map.of("present", false, "length", 0);
        }
        return Map.of(
                "present", true,
                "length", code.length(),
                "sha256", sha256(code),
                "firstLine", truncate(firstLine(code), TEXT_PREVIEW_MAX)
        );
    }

    private Map<String, Object> textSummary(String text) {
        if (text == null || text.isBlank()) {
            return Map.of("present", false, "length", 0);
        }
        return Map.of(
                "present", true,
                "length", text.length(),
                "preview", truncate(text.replaceAll("\\s+", " ").trim(), TEXT_PREVIEW_MAX)
        );
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"error\":\"audit summary serialization failed\"}";
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return "";
        }
    }

    private String firstLine(String value) {
        int index = value.indexOf('\n');
        return index < 0 ? value : value.substring(0, index);
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.get(field) == null || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private String status(String value) {
        return nonBlank(value, "UNKNOWN");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
