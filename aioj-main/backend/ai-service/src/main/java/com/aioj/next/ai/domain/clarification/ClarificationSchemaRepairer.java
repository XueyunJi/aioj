package com.aioj.next.ai.domain.clarification;

import com.aioj.next.ai.domain.AiCompletion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ClarificationSchemaRepairer {
    private static final Set<String> KINDS = Set.of(
            "single_choice", "multi_choice", "free_text", "code", "number", "file", "confirm", "mixed"
    );

    public AiCompletion.Clarification repair(AiCompletion.Clarification clarification) {
        String originalDefaultAction = clarification == null ? "" : clarification.defaultAction();
        AiCompletion.Clarification normalized = clarification == null ? AiCompletion.Clarification.empty() : clarification.normalized();
        List<AiCompletion.ClarificationOption> options = normalized.options() == null ? List.of() : normalized.options();
        String text = (normalized.title() + " " + normalized.prompt()).toLowerCase(Locale.ROOT);
        String requestedKind = normalizeKind(clarification == null || clarification.input() == null ? "" : clarification.input().kind());
        String kind = normalizeKind(normalized.input() == null ? "" : normalized.input().kind());
        boolean hasOptions = !options.isEmpty();
        boolean asksForCode = containsAny(text, "代码", "粘贴代码", "current code", "code");
        boolean asksForOpenText = containsAny(text, "粘贴", "输入", "描述", "报错", "失败样例", "现象", "题面", "日志", "补充");
        boolean optionHasCustom = options.stream().anyMatch(option -> {
            String type = option.type() == null ? "" : option.type();
            return "text".equals(type) || "textarea".equals(type) || "free_text".equals(type) || "code".equals(type);
        });

        if (kind.isBlank()) {
            kind = hasOptions ? "single_choice" : "free_text";
        }
        if (asksForCode) {
            kind = hasOptions ? "mixed" : "code";
        } else if (asksForOpenText || optionHasCustom) {
            if (!hasOptions) {
                kind = "free_text";
            } else if (requestedKind.isBlank() || "mixed".equals(requestedKind) || optionHasCustom) {
                kind = "mixed";
            }
        }
        if (("single_choice".equals(kind) || "multi_choice".equals(kind)) && !hasOptions) {
            kind = "free_text";
        }
        boolean allowCustom = "mixed".equals(kind)
                || "free_text".equals(kind)
                || "code".equals(kind)
                || (normalized.input() != null && normalized.input().allowCustom());
        String customKind = "code".equals(kind) ? "code" : allowCustom ? "free_text" : null;
        String placeholder = normalized.input() == null ? "" : normalized.input().placeholder();
        if ((placeholder == null || placeholder.isBlank()) && asksForCode) {
            placeholder = "粘贴当前代码、报错或失败样例";
        } else if (placeholder == null || placeholder.isBlank()) {
            placeholder = "补充更多上下文";
        }
        AiCompletion.ClarificationInput input = new AiCompletion.ClarificationInput(
                kind,
                normalized.input() != null && normalized.input().required(),
                options,
                allowCustom,
                customKind,
                placeholder
        );
        return new AiCompletion.Clarification(
                normalized.id(),
                normalizePriority(normalized.priority()),
                normalized.title(),
                normalized.prompt(),
                input,
                options,
                normalizeDefaultAction(originalDefaultAction, kind),
                normalized.assumption()
        );
    }

    private String normalizeKind(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return KINDS.contains(normalized) ? normalized : "";
    }

    private String normalizePriority(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("blocking".equals(normalized) || "confirm".equals(normalized)) {
            return normalized;
        }
        return "helpful";
    }

    private String normalizeDefaultAction(String value, String kind) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("ask_user".equals(normalized) || "use_assumption".equals(normalized) || "continue".equals(normalized)) {
            return normalized;
        }
        return "free_text".equals(kind) || "code".equals(kind) || "mixed".equals(kind) ? "ask_user" : "continue";
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
