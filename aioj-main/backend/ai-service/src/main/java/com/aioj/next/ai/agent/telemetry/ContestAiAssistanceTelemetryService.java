package com.aioj.next.ai.agent.telemetry;

import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.model.ModelUsage;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceModelUsageEntity;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceTurnEntity;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceModelUsageMapper;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceTurnMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Observes V3 turns for the administrator's contest-assistance statistics.
 *
 * <p>This service deliberately never returns a value to the policy or Agent
 * pipeline. A telemetry persistence/classification failure is logged and must
 * not change a student's answer, tool access, quota, or guard behaviour.</p>
 */
@Service
public class ContestAiAssistanceTelemetryService {
    private static final Logger log = LoggerFactory.getLogger(ContestAiAssistanceTelemetryService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final long WINDOW_GRACE_SECONDS = 60L;

    public static final String TOKEN_ACCOUNTING_COMPLETE = "COMPLETE";
    public static final String TOKEN_ACCOUNTING_PARTIAL = "PARTIAL";
    public static final String USAGE_REPORTED = "REPORTED";
    public static final String USAGE_MISSING = "MISSING";
    public static final String SOURCE_UNDERSTANDING = "UNDERSTANDING";
    public static final String SOURCE_AGENT_PRIMARY = "AGENT_PRIMARY";
    public static final String SOURCE_AGENT_SAFE_REGENERATION = "AGENT_SAFE_REGENERATION";
    public static final String SOURCE_AGENT_RECHECK_REGENERATION = "AGENT_RECHECK_REGENERATION";
    public static final String SOURCE_INTENT_JUDGE = "INTENT_JUDGE";

    private final AiContestAssistanceTurnMapper turnMapper;
    private final AiContestAssistanceModelUsageMapper usageMapper;
    private final ContestAssistanceIntentJudge intentJudge;

    public ContestAiAssistanceTelemetryService(AiContestAssistanceTurnMapper turnMapper,
                                               AiContestAssistanceModelUsageMapper usageMapper,
                                               ContestAssistanceIntentJudge intentJudge) {
        this.turnMapper = turnMapper;
        this.usageMapper = usageMapper;
        this.intentJudge = intentJudge;
    }

    /** Handle carried only by the current coordinator invocation; inactive handles are no-ops. */
    public record TrackingContext(Long assistanceTurnId, String turnId) {
        public boolean active() {
            return assistanceTurnId != null && turnId != null && !turnId.isBlank();
        }

        public static TrackingContext inactive() {
            return new TrackingContext(null, null);
        }
    }

    /**
     * Creates the one ledger row only after L1 has returned a server-authoritative
     * attributed run. The V3 guard may remain active beyond this reporting window;
     * telemetry intentionally stops at end + 60 seconds.
     */
    public TrackingContext begin(AiTurnEntity turn, AiConversationEntity conversation,
                                 RunningContestParticipation attributed) {
        if (turn == null || turn.getId() == null || conversation == null || attributed == null
                || attributed.contestId() == null || attributed.contestRunId() == null
                || !withinReportingWindow(turn.getCreatedAt(), attributed)) {
            return TrackingContext.inactive();
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            AiContestAssistanceTurnEntity entity = new AiContestAssistanceTurnEntity();
            entity.setTurnId(turn.getId());
            entity.setUserId(conversation.getUserId());
            entity.setContestId(attributed.contestId());
            entity.setContestRunId(attributed.contestRunId());
            entity.setConversationId(conversation.getId());
            entity.setInterceptType(ContestAssistanceIntentJudge.InterceptType.NONE.name());
            entity.setIntentStatus(ContestAssistanceIntentJudge.Status.PENDING.name());
            entity.setTokenAccountingStatus(TOKEN_ACCOUNTING_COMPLETE);
            entity.setStartedAt(turn.getCreatedAt() == null ? now : turn.getCreatedAt());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            turnMapper.insert(entity);
            return new TrackingContext(entity.getId(), entity.getTurnId());
        } catch (DuplicateKeyException ignored) {
            AiContestAssistanceTurnEntity existing = turnMapper.selectOne(
                    new LambdaQueryWrapper<AiContestAssistanceTurnEntity>()
                            .eq(AiContestAssistanceTurnEntity::getTurnId, turn.getId())
                            .last("LIMIT 1"));
            return existing == null ? TrackingContext.inactive()
                    : new TrackingContext(existing.getId(), existing.getTurnId());
        } catch (RuntimeException ex) {
            log.error("contest assistance telemetry start failed turn={} error={}", turn.getId(), ex.toString());
            return TrackingContext.inactive();
        }
    }

    public void recordUsage(TrackingContext context, String usageKey, String usageSource, ModelUsage usage) {
        if (context == null || !context.active() || usage == null) {
            return;
        }
        try {
            AiContestAssistanceModelUsageEntity entity = new AiContestAssistanceModelUsageEntity();
            entity.setAssistanceTurnId(context.assistanceTurnId());
            entity.setTurnId(context.turnId());
            entity.setUsageKey(usageKey);
            entity.setUsageSource(usageSource);
            entity.setProvider(usage.provider());
            entity.setModel(usage.model());
            entity.setPromptTokens(Math.max(0L, usage.promptTokens()));
            entity.setCompletionTokens(Math.max(0L, usage.completionTokens()));
            entity.setUsageStatus(usage.reported() ? USAGE_REPORTED : USAGE_MISSING);
            entity.setCreatedAt(LocalDateTime.now());
            usageMapper.insert(entity);
            if (!usage.reported()) {
                markTokenAccountingPartial(context);
            }
        } catch (DuplicateKeyException ignored) {
            // A reconnect/retry must keep exactly one usage event for this logical call.
        } catch (RuntimeException ex) {
            log.error("contest assistance token telemetry failed turn={} source={} error={}",
                    context.turnId(), usageSource, ex.toString());
            markTokenAccountingPartial(context);
        }
    }

    public void markTokenAccountingPartial(TrackingContext context) {
        if (context == null || !context.active()) {
            return;
        }
        try {
            turnMapper.update(null, new UpdateWrapper<AiContestAssistanceTurnEntity>()
                    .eq("id", context.assistanceTurnId())
                    .set("token_accounting_status", TOKEN_ACCOUNTING_PARTIAL)
                    .set("updated_at", LocalDateTime.now()));
        } catch (RuntimeException ex) {
            log.error("contest assistance token partial marker failed turn={} error={}", context.turnId(), ex.toString());
        }
    }

    /** Completes telemetry for a successfully completed/refused V3 turn. */
    public void finishSuccessful(TrackingContext context, String terminalStatus, String userMessage,
                                 List<ContestAssistanceIntentJudge.Candidate> candidates) {
        if (context == null || !context.active()) {
            return;
        }
        ContestAssistanceIntentJudge.Judgement judgement = resolveTrustedPrivateMessageMatch(
                intentJudge.assess(userMessage, candidates), candidates);
        if (judgement.usage() != null) {
            recordUsage(context, SOURCE_INTENT_JUDGE, SOURCE_INTENT_JUDGE, judgement.usage());
        } else if (judgement.status() == ContestAssistanceIntentJudge.Status.UNAVAILABLE) {
            markTokenAccountingPartial(context);
        }
        updateTerminal(context, terminalStatus, judgement.interceptType().name(), judgement.status().name());
    }

    /** Failed provider/setup turns count as a turn but never as an interception. */
    public void finishFailed(TrackingContext context, String terminalStatus) {
        if (context == null || !context.active()) {
            return;
        }
        markTokenAccountingPartial(context);
        updateTerminal(context, terminalStatus, ContestAssistanceIntentJudge.InterceptType.NONE.name(),
                ContestAssistanceIntentJudge.Status.SKIPPED.name());
    }

    /** Timeout/setup cleanup may run outside the coordinator's current handle. */
    public void finishFailedByTurnId(String turnId, String terminalStatus) {
        if (turnId == null || turnId.isBlank()) {
            return;
        }
        try {
            AiContestAssistanceTurnEntity entity = turnMapper.selectOne(
                    new LambdaQueryWrapper<AiContestAssistanceTurnEntity>()
                            .eq(AiContestAssistanceTurnEntity::getTurnId, turnId)
                            .last("LIMIT 1"));
            if (entity != null) {
                finishFailed(new TrackingContext(entity.getId(), entity.getTurnId()), terminalStatus);
            }
        } catch (RuntimeException ex) {
            log.error("contest assistance terminal failure telemetry lookup failed turn={} error={}", turnId, ex.toString());
        }
    }

    /**
     * Builds only safe statistical context. Client problem identifiers are merely
     * hints and become candidates only after matching a server snapshot occurrence
     * in the same L1-attributed run.
     */
    public List<ContestAssistanceIntentJudge.Candidate> candidates(
            AiChatRequest request,
            AiConversationEntity conversation,
            PolicySnapshotService.PolicySnapshot snapshot,
            RunningContestParticipation attributed,
            GuardVerdict messageVerdict,
            GuardVerdict runtimeVerdict
    ) {
        if (snapshot == null || attributed == null || attributed.contestRunId() == null) {
            return List.of();
        }
        LinkedHashMap<String, ContestAssistanceIntentJudge.Candidate> candidates = new LinkedHashMap<>();
        addFingerprintCandidates(candidates, messageVerdict, attributed,
                ContestAssistanceIntentJudge.Candidate.SOURCE_MESSAGE_FINGERPRINT);
        addFingerprintCandidates(candidates, runtimeVerdict, attributed,
                ContestAssistanceIntentJudge.Candidate.SOURCE_CONTEXT_FINGERPRINT);
        Set<Long> hintedProblemIds = new LinkedHashSet<>();
        if (request != null && request.problemId() != null) {
            hintedProblemIds.add(request.problemId());
        }
        if (conversation != null) {
            if (conversation.getProblemId() != null) {
                hintedProblemIds.add(conversation.getProblemId());
            }
            if (conversation.getRecentProblemId() != null) {
                hintedProblemIds.add(conversation.getRecentProblemId());
            }
        }
        for (RunningContestProblemStatement problem : snapshot.constrainedProblems()) {
            if (problem == null || problem.problemId() == null || !hintedProblemIds.contains(problem.problemId())
                    || !belongsToRun(problem, attributed.contestRunId())) {
                continue;
            }
            addCandidate(candidates, problem.visibility() == null ? null : problem.visibility().name(),
                    ContestAssistanceIntentJudge.Candidate.SOURCE_TRUSTED_ENTRY_CONTEXT);
        }
        return List.copyOf(candidates.values());
    }

    private void addFingerprintCandidates(LinkedHashMap<String, ContestAssistanceIntentJudge.Candidate> candidates,
                                          GuardVerdict verdict, RunningContestParticipation attributed, String source) {
        if (verdict == null || !verdict.hasMatches()) {
            return;
        }
        verdict.matchedProblems().stream()
                .filter(ref -> ref != null && attributed.contestRunId().equals(ref.contestRunId())
                        && attributed.contestId().equals(ref.contestId()))
                .forEach(ref -> addCandidate(candidates, ref.visibility(), source));
    }

    /**
     * A completed structured judgement is normally the sole statistical
     * classifier. A current-message L3 private fingerprint is a stricter,
     * server-authoritative fact, however: it prevents a model false negative
     * from undercounting a turn that the contest mechanism actually refused.
     *
     * <p>The fallback never upgrades {@code UNAVAILABLE}; failures remain
     * deliberately uncounted. It changes the classification of the same ledger
     * row only, so a turn can still contribute at most one interception.</p>
     */
    private ContestAssistanceIntentJudge.Judgement resolveTrustedPrivateMessageMatch(
            ContestAssistanceIntentJudge.Judgement judgement,
            List<ContestAssistanceIntentJudge.Candidate> candidates
    ) {
        if (judgement == null
                || judgement.status() != ContestAssistanceIntentJudge.Status.COMPLETED
                || candidates == null
                || candidates.stream().noneMatch(ContestAssistanceIntentJudge.Candidate::isTrustedPrivateMessageMatch)) {
            return judgement;
        }
        if (judgement.interceptType() == ContestAssistanceIntentJudge.InterceptType.PRIVATE_CONTEST_QUESTION) {
            return judgement;
        }
        return new ContestAssistanceIntentJudge.Judgement(
                ContestAssistanceIntentJudge.InterceptType.PRIVATE_CONTEST_QUESTION,
                judgement.status(),
                judgement.usage());
    }

    private void addCandidate(LinkedHashMap<String, ContestAssistanceIntentJudge.Candidate> candidates,
                              String visibility, String source) {
        if (!"PRIVATE".equals(visibility) && !"PUBLIC".equals(visibility)) {
            return;
        }
        String key = visibility + '|' + source;
        candidates.putIfAbsent(key, new ContestAssistanceIntentJudge.Candidate(visibility, source));
    }

    private boolean belongsToRun(RunningContestProblemStatement problem, Long contestRunId) {
        if (contestRunId.equals(problem.contestRunId())) {
            return true;
        }
        List<RunningContestProblemOccurrence> occurrences = problem.occurrences();
        return occurrences != null && occurrences.stream()
                .anyMatch(occurrence -> occurrence != null && contestRunId.equals(occurrence.contestRunId()));
    }

    private boolean withinReportingWindow(LocalDateTime startedAt, RunningContestParticipation attributed) {
        Instant at = (startedAt == null ? LocalDateTime.now() : startedAt).atZone(ZONE).toInstant();
        if (attributed.startAt() != null && at.isBefore(attributed.startAt())) {
            return false;
        }
        return attributed.endAt() == null || !at.isAfter(attributed.endAt().plusSeconds(WINDOW_GRACE_SECONDS));
    }

    private void updateTerminal(TrackingContext context, String terminalStatus, String interceptType, String intentStatus) {
        try {
            LocalDateTime now = LocalDateTime.now();
            turnMapper.update(null, new UpdateWrapper<AiContestAssistanceTurnEntity>()
                    .eq("id", context.assistanceTurnId())
                    .set("terminal_status", terminalStatus)
                    .set("intercept_type", interceptType)
                    .set("intent_status", intentStatus)
                    .set("completed_at", now)
                    .set("updated_at", now));
        } catch (RuntimeException ex) {
            log.error("contest assistance terminal telemetry failed turn={} error={}", context.turnId(), ex.toString());
        }
    }
}
