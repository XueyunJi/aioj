package com.aioj.next.ai.domain.problem;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiProvider;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProblemDraftRepairer {
    private static final int MAX_REPAIR_REASON_LENGTH = 1000;
    private static final List<String> CONTENT_FIELDS = List.of(
            "title", "difficulty", "statement", "notes", "standardSolutionLanguage", "standardSolutionCode",
            "referenceSolutionLanguage", "referenceSolutionCode", "testcaseGeneratorPython",
            "stressTestcaseGeneratorPython", "generationPlan", "tags", "testCases", "timeLimitMillis", "memoryLimitKb"
    );

    private final AiProvider aiProvider;
    private final ObjectMapper objectMapper;
    private final VerificationFailureClassifier failureClassifier;
    private final int maxRepairAttempts;

    public ProblemDraftRepairer(AiProvider aiProvider, ObjectMapper objectMapper, AiProperties properties) {
        this.aiProvider = aiProvider;
        this.objectMapper = objectMapper;
        this.failureClassifier = new VerificationFailureClassifier(objectMapper);
        this.maxRepairAttempts = properties == null || properties.getProblemDraft() == null
                ? 2
                : Math.max(0, properties.getProblemDraft().getMaxRepairAttempts());
    }

    public int maxRepairAttempts() {
        return maxRepairAttempts;
    }

    public ProblemDraftResponse repair(ProblemDraftResponse draft, ProblemDraftRequest request,
                                       String verificationReportJson, int attempt, int maxAttempts) {
        RepairTask repairTask = failureClassifier.classify(verificationReportJson);
        ProblemDraftRepairPatch patch = aiProvider.repairProblemDraft(
                draft.id(),
                draft,
                repairContextJson(verificationReportJson, repairTask),
                toJson(request),
                attempt,
                maxAttempts
        );
        return applyPatch(draft, patch == null ? ProblemDraftRepairPatch.empty("Provider returned no repair patch") : patch,
                attempt, repairTask);
    }

    ProblemDraftResponse applyPatch(ProblemDraftResponse draft, ProblemDraftRepairPatch patch, int attempt) {
        return applyPatch(draft, patch, attempt, new RepairTask(
                "UNSCOPED_REPAIR",
                "all draft content fields",
                1.0,
                CONTENT_FIELDS,
                List.of(),
                List.of()
        ));
    }

    ProblemDraftResponse applyPatch(ProblemDraftResponse draft, ProblemDraftRepairPatch patch, int attempt,
                                    RepairTask repairTask) {
        Set<String> changedFields = changedFields(patch.changedFields());
        boolean explicitChanges = !changedFields.isEmpty();
        Set<String> allowedFields = allowedFields(repairTask);
        String repairReason = repairReason(patch.repairReason(), attempt);
        return new ProblemDraftResponse(
                draft.id(),
                draft.status(),
                applyString("title", draft.title(), patch.title(), explicitChanges, changedFields, allowedFields),
                applyString("difficulty", draft.difficulty(), patch.difficulty(), explicitChanges, changedFields, allowedFields),
                applyString("statement", draft.statement(), patch.statement(), explicitChanges, changedFields, allowedFields),
                applyString("notes", draft.notes(), patch.notes(), explicitChanges, changedFields, allowedFields),
                applyString("standardSolutionLanguage", draft.standardSolutionLanguage(), patch.standardSolutionLanguage(),
                        explicitChanges, changedFields, allowedFields),
                applyString("standardSolutionCode", draft.standardSolutionCode(), patch.standardSolutionCode(),
                        explicitChanges, changedFields, allowedFields),
                applyString("referenceSolutionLanguage", draft.referenceSolutionLanguage(), patch.referenceSolutionLanguage(),
                        explicitChanges, changedFields, allowedFields),
                applyString("referenceSolutionCode", draft.referenceSolutionCode(), patch.referenceSolutionCode(),
                        explicitChanges, changedFields, allowedFields),
                applyString("testcaseGeneratorPython", draft.testcaseGeneratorPython(), patch.testcaseGeneratorPython(),
                        explicitChanges, changedFields, allowedFields),
                applyString("stressTestcaseGeneratorPython", draft.stressTestcaseGeneratorPython(),
                        patch.stressTestcaseGeneratorPython(), explicitChanges, changedFields, allowedFields),
                applyString("generationPlan", draft.generationPlan(), patch.generationPlan(), explicitChanges, changedFields,
                        allowedFields),
                applyList("tags", draft.tags(), patch.tags(), explicitChanges, changedFields, allowedFields),
                draft.validationStatus(),
                draft.validationErrors(),
                applyList("testCases", draft.testCases(), patch.testCases(), explicitChanges, changedFields, allowedFields),
                applyInteger("timeLimitMillis", draft.timeLimitMillis(), patch.timeLimitMillis(), explicitChanges, changedFields,
                        allowedFields),
                applyInteger("memoryLimitKb", draft.memoryLimitKb(), patch.memoryLimitKb(), explicitChanges, changedFields,
                        allowedFields),
                draft.importedProblemId(),
                nonBlank(patch.model(), draft.model()),
                draft.promptTokens() + patch.promptTokens(),
                draft.completionTokens() + patch.completionTokens(),
                draft.createdAt() == null ? Instant.now() : draft.createdAt(),
                draft.archivedAt(),
                draft.deletedAt(),
                draft.deletedBy(),
                draft.refinedFromDraftId(),
                draft.refineNote(),
                "NOT_RUN",
                null,
                Math.max(0, attempt),
                repairReason
        );
    }

    private Set<String> allowedFields(RepairTask repairTask) {
        if (repairTask == null || repairTask.allowedFields().isEmpty()) {
            return Set.of();
        }
        return repairTask.allowedFields().stream()
                .filter(field -> field != null && !field.isBlank())
                .map(field -> field.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> changedFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return Set.of();
        }
        return fields.stream()
                .filter(field -> field != null && !field.isBlank())
                .map(field -> field.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String applyString(String field, String current, String patch, boolean explicitChanges, Set<String> changedFields,
                               Set<String> allowedFields) {
        return patch != null && shouldApply(field, explicitChanges, changedFields, allowedFields) ? patch : current;
    }

    private Integer applyInteger(String field, Integer current, Integer patch, boolean explicitChanges,
                                 Set<String> changedFields, Set<String> allowedFields) {
        return patch != null && shouldApply(field, explicitChanges, changedFields, allowedFields) ? patch : current;
    }

    private <T> List<T> applyList(String field, List<T> current, List<T> patch, boolean explicitChanges,
                                  Set<String> changedFields, Set<String> allowedFields) {
        return patch != null && shouldApply(field, explicitChanges, changedFields, allowedFields) ? patch : current;
    }

    private boolean shouldApply(String field, boolean explicitChanges, Set<String> changedFields, Set<String> allowedFields) {
        if (!isAllowed(field, allowedFields)) {
            return false;
        }
        if (!explicitChanges) {
            return true;
        }
        String normalized = field.toLowerCase(Locale.ROOT);
        if (changedFields.contains(normalized)) {
            return true;
        }
        return changedFields.stream()
                .anyMatch(changedField -> changedField.startsWith(normalized + ".")
                        || changedField.startsWith(normalized + "["));
    }

    private boolean isAllowed(String field, Set<String> allowedFields) {
        if (allowedFields.isEmpty()) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT);
        if (allowedFields.contains(normalized)) {
            return true;
        }
        return allowedFields.stream()
                .anyMatch(allowedField -> normalized.startsWith(allowedField + ".")
                        || normalized.startsWith(allowedField + "["));
    }

    private String repairReason(String value, int attempt) {
        String reason = nonBlank(value, "Auto repair attempt " + attempt);
        return reason.length() > MAX_REPAIR_REASON_LENGTH ? reason.substring(0, MAX_REPAIR_REASON_LENGTH) : reason;
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(ProblemDraftRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String repairContextJson(String verificationReportJson, RepairTask repairTask) {
        try {
            Object report = verificationReportJson == null || verificationReportJson.isBlank()
                    ? null
                    : objectMapper.readTree(verificationReportJson);
            return objectMapper.writeValueAsString(new RepairContext(repairTask, report, report == null ? verificationReportJson : null));
        } catch (Exception ex) {
            try {
                return objectMapper.writeValueAsString(new RepairContext(repairTask, null, verificationReportJson));
            } catch (JsonProcessingException nested) {
                return verificationReportJson == null ? "{}" : verificationReportJson;
            }
        }
    }

    private record RepairContext(RepairTask repairTask, Object verificationReport, String verificationReportText) {
    }
}
