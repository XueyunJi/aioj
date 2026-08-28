package com.aioj.next.ai.agent.guard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared rendering for L3 fingerprint-hit annotation blocks (design doc §5.3).
 * Both matching layers (message layer in the bootstrap, assembled-context layer
 * in the agent loop) tell the model exactly which restricted problems matched
 * and which rule applies, in the same rule-line vocabulary, so a rule line has
 * one authoritative wording no matter which layer produced it.
 */
public final class ContestGuardMatchText {

    private ContestGuardMatchText() {
    }

    /** One matched problem to render a rule line for (deduplicated by problemId). */
    public record RuleLine(Long problemId, String visibility, String aiPolicyMode, String aiPolicyNotes) {
    }

    /** Rule text shared by both L3 layers; mirrors the L2 snapshot rule lines. */
    public static String ruleOf(String visibility, String aiPolicyMode) {
        if ("STRICT".equals(aiPolicyMode)) {
            return "refuse any question materially about this problem (STRICT run policy)";
        }
        if ("PRIVATE".equals(visibility)) {
            return "private contest problem: refuse to discuss its content, solution, or code; decline politely";
        }
        return "public contest problem: hints and idea-level guidance only; never output complete submittable code";
    }

    /**
     * Renders one {@code [Contest Guard Match]} block. Rule lines are deduplicated
     * by problemId (first occurrence wins) and carry run notes when present.
     *
     * @param headerSource identifies the matching layer in the header line
     *                     (e.g. "server fingerprint result for the current message")
     * @param matchLead    the lead-in sentence above the rule lines
     */
    public static String renderBlock(String headerSource, String matchLead, List<RuleLine> lines) {
        Map<Long, RuleLine> byProblem = new LinkedHashMap<>();
        for (RuleLine line : lines) {
            byProblem.putIfAbsent(line.problemId(), line);
        }
        StringBuilder content = new StringBuilder("[Contest Guard Match — " + headerSource + "; enforce]\n");
        content.append(matchLead).append("\n");
        for (RuleLine line : byProblem.values()) {
            content.append("- Problem #").append(line.problemId())
                    .append(" (").append(line.visibility() == null ? "UNKNOWN" : line.visibility())
                    .append(", policy ").append(line.aiPolicyMode() == null ? "DEFAULT" : line.aiPolicyMode())
                    .append("): ").append(ruleOf(line.visibility(), line.aiPolicyMode()))
                    .append(line.aiPolicyNotes() == null || line.aiPolicyNotes().isBlank()
                            ? "" : " Run notes: " + line.aiPolicyNotes())
                    .append("\n");
        }
        content.append("Apply the Contest Participation Policy to these problems. When refusing, "
                + "stay brief and do not restate the matched content.");
        content.append("\n[/Contest Guard Match]");
        return content.toString();
    }
}
