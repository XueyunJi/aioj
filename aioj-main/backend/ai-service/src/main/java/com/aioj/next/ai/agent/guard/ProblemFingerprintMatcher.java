package com.aioj.next.ai.agent.guard;

import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L3 ProblemFingerprintMatcher (design doc §5.3): deterministic, cheap text
 * fingerprints of the user's text against the deduplicated running-contest
 * problem set (never the whole problem bank, frozen decision).
 *
 * <p>Algorithms: normalized exact containment (score 1.0) and character-ngram
 * containment (fraction of the statement's shingles present in the user text).
 * Hit criterion: score ≥ {@code ai.agent-core.fingerprint-containment-threshold}
 * (default 0.45). Pure and side-effect free — callers persist the audit
 * decision so both layers (message / assembled context) share this matcher.
 * Paraphrase/translation variants are primarily the L2 model's job (§5.3);
 * embedding similarity is an optional P4 enhancement.</p>
 */
@Service
public class ProblemFingerprintMatcher {

    /** Character shingle size for CJK-mixed text. */
    static final int NGRAM_SIZE = 6;
    /** Statements with fewer shingles than this are skipped for fuzzy matching (false-positive guard). */
    static final int MIN_STATEMENT_SHINGLES = 8;

    private final double containmentThreshold;
    private final Map<String, Set<Integer>> shingleCache = new ConcurrentHashMap<>();

    @Autowired
    public ProblemFingerprintMatcher(AiProperties properties) {
        this.containmentThreshold = properties.getAgentCore().getFingerprintContainmentThreshold();
    }

    ProblemFingerprintMatcher(double containmentThreshold) {
        this.containmentThreshold = containmentThreshold;
    }

    public GuardVerdict match(String userText, List<RunningContestProblemStatement> candidates) {
        if (userText == null || userText.isBlank() || candidates == null || candidates.isEmpty()) {
            return GuardVerdict.pass();
        }
        String normalizedText = normalize(userText);
        Set<Integer> textShingles = shingles(normalizedText);
        List<GuardDecisionRecorder.MatchedProblemRef> matched = new ArrayList<>();
        double maxScore = 0.0;
        for (RunningContestProblemStatement problem : candidates) {
            if (problem.statement() == null || problem.statement().isBlank()) {
                continue;
            }
            double score = score(normalizedText, textShingles, problem.statement());
            if (score >= containmentThreshold) {
                matched.addAll(refsOf(problem));
                maxScore = Math.max(maxScore, score);
            }
        }
        if (matched.isEmpty()) {
            return GuardVerdict.pass();
        }
        return GuardVerdict.constrain(matched, maxScore);
    }

    private double score(String normalizedText, Set<Integer> textShingles, String statement) {
        String normalizedStatement = normalize(statement);
        if (normalizedStatement.length() >= NGRAM_SIZE * 2 && normalizedText.contains(normalizedStatement)) {
            return 1.0;
        }
        Set<Integer> statementShingles = shingleCache.computeIfAbsent(
                digest(normalizedStatement), key -> shingles(normalizedStatement));
        if (statementShingles.size() < MIN_STATEMENT_SHINGLES || textShingles.isEmpty()) {
            return 0.0;
        }
        Set<Integer> intersection = new HashSet<>(statementShingles);
        intersection.retainAll(textShingles);
        return (double) intersection.size() / (double) statementShingles.size();
    }

    private List<GuardDecisionRecorder.MatchedProblemRef> refsOf(RunningContestProblemStatement problem) {
        List<GuardDecisionRecorder.MatchedProblemRef> refs = new ArrayList<>();
        String visibility = problem.visibility() == null ? null : problem.visibility().name();
        String mode = problem.aiPolicyMode() == null ? null : problem.aiPolicyMode().name();
        List<RunningContestProblemOccurrence> occurrences = problem.occurrences();
        if (occurrences == null || occurrences.isEmpty()) {
            refs.add(new GuardDecisionRecorder.MatchedProblemRef(
                    problem.problemId(), problem.contestId(), problem.contestRunId(),
                    problem.contestProblemId(), visibility, mode));
            return refs;
        }
        for (RunningContestProblemOccurrence occurrence : occurrences) {
            refs.add(new GuardDecisionRecorder.MatchedProblemRef(
                    problem.problemId(), occurrence.contestId(), occurrence.contestRunId(),
                    occurrence.contestProblemId(), visibility, mode));
        }
        return refs;
    }

    /**
     * Lowercase, full-width to half-width, keep letters/digits/CJK, drop every
     * other character (whitespace, punctuation, markdown). Paste variants with
     * reformatted whitespace still match.
     */
    static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '！' && c <= '～') {
                c = (char) (c - 0xFEE0);
            } else if (c == 0x3000) {
                c = ' ';
            }
            if (Character.isLetterOrDigit(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static Set<Integer> shingles(String normalized) {
        Set<Integer> shingles = new HashSet<>();
        if (normalized.length() < NGRAM_SIZE) {
            if (!normalized.isEmpty()) {
                shingles.add(normalized.hashCode());
            }
            return shingles;
        }
        for (int i = 0; i + NGRAM_SIZE <= normalized.length(); i++) {
            shingles.add(normalized.substring(i, i + NGRAM_SIZE).hashCode());
        }
        return shingles;
    }

    private static String digest(String normalized) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            return String.valueOf(normalized.hashCode());
        }
    }
}
