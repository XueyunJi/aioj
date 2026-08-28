package com.aioj.next.ai.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AiResponsePolicyGuard {
    private static final Logger log = LoggerFactory.getLogger(AiResponsePolicyGuard.class);
    private static final String SAFE_CONTEST_MESSAGE = """
            比赛进行中我不能给出完整可提交代码或标程。

            我可以继续帮你做这些事：
            - 根据你描述的思路检查漏洞；
            - 分析复杂度是否可能超时；
            - 提醒边界情况和反例构造方向；
            - 根据评测状态给出调试检查清单。

            你可以继续描述你的思路、关键变量或报错现象，我会按比赛规则给调试方向。
            """;

    /**
     * Replaces submit-ready code in assistant responses of a constrained turn (contest
     * guard CONSTRAIN decision or an active-contest selected submission context).
     */
    public GuardedCompletion guard(Long userId, String conversationId, AiCompletion completion, boolean constrained) {
        if (completion == null || !constrained) {
            return new GuardedCompletion(completion, false, null);
        }
        if (!looksLikeSubmitReadyCode(completion.content())) {
            return new GuardedCompletion(completion, false, null);
        }
        log.warn("Contest AI response guard removed submit-ready code user={} conversation={}", userId, conversationId);
        return new GuardedCompletion(new AiCompletion(
                SAFE_CONTEST_MESSAGE,
                completion.provider(),
                completion.model(),
                completion.promptTokens(),
                completion.completionTokens(),
                "DEBUG",
                completion.stuckLayer(),
                completion.studentLevel(),
                completion.clarification()
        ), true, "SUBMIT_READY_CODE_DETECTED");
    }

    private boolean looksLikeSubmitReadyCode(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String text = content.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        if (text.contains("```")) score++;
        if (lower.contains("#include") || lower.contains("using namespace std") || lower.contains("int main(")) score += 2;
        if (lower.contains("public class") && lower.contains("static void main")) score += 2;
        if (lower.contains("import sys") || lower.contains("def main") || lower.contains("if __name__")) score += 2;
        if (lower.contains("cin >>") || lower.contains("scanner") || lower.contains("sys.stdin")) score++;
        if (text.lines().count() >= 18 && (lower.contains("for ") || lower.contains("while ") || lower.contains("return"))) score++;
        return score >= 3;
    }

    public record GuardedCompletion(
            AiCompletion completion,
            boolean replaced,
            String reason
    ) {
    }
}
