package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.agent.model.GatewayMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces the bootstrap context budget (design doc §6.9). The derived
 * CONVERSATION_FOCUS section is dropped first (redundant and re-fetchable via
 * context tools); RECENT_TURNS is then trimmed oldest-first, keeping turn pairs
 * intact. Policy text and the current request are atomic — never cut mid-string.
 */
@Component
public class ContextBudgetAllocator {

    public record BudgetResult(List<ContextSection> sections, List<String> warnings) {
    }

    public BudgetResult enforce(List<ContextSection> sections, int budgetTokens) {
        int total = sections.stream().mapToInt(ContextSection::tokenEstimate).sum();
        if (total <= budgetTokens) {
            return new BudgetResult(sections, List.of());
        }
        List<String> warnings = new ArrayList<>();
        List<ContextSection> result = new ArrayList<>(sections);
        // Derived focus is the cheapest section to lose: it is redundant with recent
        // turns and re-fetchable via context tools, so it drops before any trimming.
        int focusIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).type() == ContextSectionType.CONVERSATION_FOCUS) {
                focusIndex = i;
                break;
            }
        }
        if (focusIndex >= 0) {
            result.remove(focusIndex);
            warnings.add("conversation_focus_dropped");
            total = result.stream().mapToInt(ContextSection::tokenEstimate).sum();
        }
        if (total <= budgetTokens) {
            return new BudgetResult(List.copyOf(result), List.copyOf(warnings));
        }
        int recentIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).type() == ContextSectionType.RECENT_TURNS) {
                recentIndex = i;
                break;
            }
        }
        if (recentIndex >= 0) {
            ContextSection recent = result.get(recentIndex);
            List<GatewayMessage> messages = new ArrayList<>(recent.messages());
            int overflow = total - budgetTokens;
            int freed = 0;
            while (!messages.isEmpty() && freed < overflow) {
                GatewayMessage dropped = messages.remove(0);
                freed += estimateOf(dropped);
                // Keep turn pairs intact: a dropped user message takes its assistant
                // answer with it, so the model never sees an orphaned reply.
                if ("user".equals(dropped.role()) && !messages.isEmpty()
                        && "assistant".equals(messages.get(0).role())) {
                    freed += estimateOf(messages.remove(0));
                }
            }
            if (messages.isEmpty()) {
                result.remove(recentIndex);
                warnings.add("recent_turns_dropped");
            } else if (freed > 0) {
                result.set(recentIndex, ContextSection.conversation(recent.type(), recent.priority(),
                        recent.trustLevel(), messages, recent.sources()));
                warnings.add("recent_turns_trimmed");
            }
            total = result.stream().mapToInt(ContextSection::tokenEstimate).sum();
        }
        if (total > budgetTokens) {
            warnings.add("bootstrap_over_budget");
        }
        return new BudgetResult(List.copyOf(result), List.copyOf(warnings));
    }

    private int estimateOf(GatewayMessage message) {
        String content = message.content();
        if (content == null || content.isBlank()) {
            return 0;
        }
        return (int) Math.max(1, (content.length() + 3L) / 4L);
    }
}
