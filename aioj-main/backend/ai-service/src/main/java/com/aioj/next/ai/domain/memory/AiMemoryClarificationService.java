package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class AiMemoryClarificationService {
    private final AiMemoryCandidateService candidateService;
    private final AiMemoryEventPayloadSanitizer sanitizer;
    private final MemoryQualityGate qualityGate;

    public AiMemoryClarificationService(
            AiMemoryCandidateService candidateService,
            AiMemoryEventPayloadSanitizer sanitizer,
            MemoryQualityGate qualityGate
    ) {
        this.candidateService = candidateService;
        this.sanitizer = sanitizer;
        this.qualityGate = qualityGate;
    }

    public Optional<PlannedClarification> planClarification(Long userId, String conversationId) {
        return candidateService.nextClarificationCandidate(userId)
                .flatMap(candidate -> toPlannedClarification(conversationId, candidate));
    }

    public void markAsked(Long userId, PlannedClarification plan, Long assistantMessageId) {
        if (plan == null || plan.candidateId() == null || plan.clarification() == null) {
            return;
        }
        candidateService.markAwaitingClarification(
                userId,
                plan.candidateId(),
                plan.clarification().id(),
                plan.conversationId(),
                assistantMessageId
        );
    }

    public AnswerResult applyAnswer(Long userId, String conversationId, AiChatRequest.ClarificationAnswer answer) {
        if (answer == null) {
            return AnswerResult.ignored();
        }
        Optional<AiMemoryCandidateEntity> candidate = candidateService.findByClarificationRequest(userId, answer.requestId());
        if (candidate.isEmpty()) {
            return AnswerResult.ignored();
        }
        AiMemoryCandidateEntity item = candidate.get();
        AnswerAction action = classify(answer);
        return switch (action) {
            case CONFIRM -> {
                candidateService.accept(userId, item.id, new AiMemoryCandidateActionRequest(
                        null,
                        null,
                        null,
                        null,
                        "memory_clarification_confirmed"
                ));
                yield new AnswerResult("CONFIRM", item.id, "ACTIVE");
            }
            case REJECT -> {
                candidateService.reject(userId, item.id, "memory_clarification_rejected");
                yield new AnswerResult("REJECT", item.id, "REJECTED");
            }
            case UPDATE -> applyUpdate(userId, item, answer);
            case SKIP -> {
                candidateService.returnToNeedsConfirmation(userId, item.id, "SKIPPED", "memory_clarification_skipped");
                yield new AnswerResult("SKIP", item.id, "NEEDS_CONFIRMATION");
            }
        };
    }

    private Optional<PlannedClarification> toPlannedClarification(String conversationId, AiMemoryCandidateEntity candidate) {
        String preview = safePreview(candidate.canonicalText);
        if (preview.isBlank()) {
            return Optional.empty();
        }
        String requestId = "memory_candidate_" + candidate.id;
        List<AiCompletion.ClarificationOption> options = List.of(
                new AiCompletion.ClarificationOption("confirm", "记住", "记住这条学习记忆", null, null),
                new AiCompletion.ClarificationOption("choice", "不记", "不要记住这条学习记忆", null, null),
                new AiCompletion.ClarificationOption("choice", "稍后处理", "暂时保留为候选", null, null)
        );
        AiCompletion.Clarification clarification = new AiCompletion.Clarification(
                requestId,
                "confirm",
                "确认学习记忆",
                "我捕捉到一个可能长期有用的" + categoryLabel(candidate.category) + "：" + preview + "。需要把它记到你的 AI 学习记忆里吗？",
                new AiCompletion.ClarificationInput(
                        "mixed",
                        false,
                        options,
                        true,
                        "free_text",
                        "如果需要修改，请直接写成你希望我记住的表述"
                ),
                options,
                "ask_user",
                "如果你暂时不处理，我会先保留为候选，不会直接写入长期记忆。"
        );
        return Optional.of(new PlannedClarification(candidate.id, conversationId, clarification));
    }

    private AnswerResult applyUpdate(Long userId, AiMemoryCandidateEntity candidate, AiChatRequest.ClarificationAnswer answer) {
        String updated = safeAnswer(answer.customText());
        if (updated.isBlank()) {
            candidateService.returnToNeedsConfirmation(userId, candidate.id, "UNUSABLE", "memory_clarification_answer_blank");
            return new AnswerResult("SKIP", candidate.id, "NEEDS_CONFIRMATION");
        }
        MemoryQualityGate.GateResult gate = qualityGate.evaluate(
                new MemoryQualityGate.MemoryCandidate(
                        candidate.category,
                        candidate.memoryKey,
                        updated,
                        candidate.valueJson,
                        candidate.scopeType,
                        candidate.scopeId,
                        "USER_MANUAL",
                        1.0,
                        true,
                        false,
                        false,
                        false,
                        false
                ),
                new MemoryQualityGate.MessageContext(updated, "")
        );
        if (!gate.accepted()) {
            candidateService.returnToNeedsConfirmation(userId, candidate.id, "UNUSABLE", firstNonBlank(gate.rejectedReason(), "memory_clarification_rejected_by_quality_gate"));
            return new AnswerResult("SKIP", candidate.id, "NEEDS_CONFIRMATION");
        }
        candidateService.accept(userId, candidate.id, new AiMemoryCandidateActionRequest(
                null,
                null,
                null,
                updated,
                "memory_clarification_updated"
        ));
        return new AnswerResult("UPDATE", candidate.id, "ACTIVE");
    }

    private AnswerAction classify(AiChatRequest.ClarificationAnswer answer) {
        String custom = normalize(answer.customText());
        String text = normalize((answer.answerText() == null ? "" : answer.answerText()) + "\n"
                + (answer.selectedOptionIds() == null ? "" : String.join("\n", answer.selectedOptionIds())) + "\n"
                + custom).toLowerCase(Locale.ROOT);
        if (containsAny(text, "不记", "不要记", "不用记", "拒绝", "否", "不需要", "reject", "no")) {
            return AnswerAction.REJECT;
        }
        if (containsAny(text, "稍后", "以后", "跳过", "不确定", "再说", "later", "skip", "not sure")) {
            return AnswerAction.SKIP;
        }
        if (!custom.isBlank() && !looksLikeShortAffirmation(custom)) {
            return AnswerAction.UPDATE;
        }
        if (containsAny(text, "记住", "确认", "可以", "是", "对", "掌握", "yes", "confirm", "accept")) {
            return AnswerAction.CONFIRM;
        }
        return AnswerAction.SKIP;
    }

    private boolean looksLikeShortAffirmation(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return normalized.length() <= 20
                && containsAny(normalized, "是", "对", "可以", "确认", "记住", "yes", "ok", "confirm");
    }

    private String safePreview(String value) {
        return truncate(stripSanitizerMarkers(sanitizer.sanitizeText(value)), 220);
    }

    private String safeAnswer(String value) {
        return truncate(stripSanitizerMarkers(sanitizer.sanitizeText(value)), 1000);
    }

    private String stripSanitizerMarkers(String value) {
        StringBuilder builder = new StringBuilder();
        for (String rawLine : normalize(value).split("\n")) {
            String line = rawLine.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.isBlank()
                    || lower.contains("[omitted]")
                    || lower.contains("[code block omitted]")
                    || lower.contains("[code line omitted]")
                    || lower.contains("[raw output omitted]")
                    || containsAny(lower, "token", "password", "secret", "api key", "apikey", "private key", "cookie", "密钥", "密码", "令牌")) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String categoryLabel(String value) {
        return switch (normalize(value).toUpperCase(Locale.ROOT)) {
            case "WEAKNESS" -> "候选薄弱点";
            case "GOAL" -> "学习目标";
            case "PROFILE" -> "学习画像";
            case "PREFERENCE" -> "偏好";
            case "RULE" -> "规则";
            case "HABIT" -> "习惯";
            default -> "学习信号";
        };
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String first, String second) {
        String normalized = normalize(first);
        return normalized.isBlank() ? normalize(second) : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String truncate(String value, int max) {
        String normalized = normalize(value);
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private enum AnswerAction {
        CONFIRM,
        REJECT,
        UPDATE,
        SKIP
    }

    public record PlannedClarification(Long candidateId, String conversationId, AiCompletion.Clarification clarification) {
    }

    public record AnswerResult(String action, Long candidateId, String status) {
        static AnswerResult ignored() {
            return new AnswerResult("IGNORED", null, "");
        }
    }
}
