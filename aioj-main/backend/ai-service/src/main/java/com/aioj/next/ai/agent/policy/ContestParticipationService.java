package com.aioj.next.ai.agent.policy;

import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * L1 of the contest defense (design doc §5.1): decides per turn whether the user
 * is a participant of any running contest run. Semantics follow Wave A (already
 * implemented problem-service side): the endpoint returns running runs plus the
 * configured grace tail; attribution prefers a run still in progress, otherwise
 * the first grace run. INVITED-but-not-accepted users are not returned by the
 * endpoint, so they are NON_PARTICIPANT here.
 *
 * <p>Fail-closed (frozen Q5): when the participation lookup itself fails the
 * turn is refused with SERVICE_UNAVAILABLE and audited with degraded=true —
 * we never silently treat an unverifiable user as a non-participant.</p>
 */
@Service
public class ContestParticipationService {

    private static final Logger log = LoggerFactory.getLogger(ContestParticipationService.class);

    public static final String REASON_NON_PARTICIPANT = "non_participant";
    public static final String REASON_PARTICIPANT = "participant";
    public static final String REASON_LOOKUP_FAILED = "participation_lookup_failed";

    private final ProblemServiceClient problemServiceClient;
    private final GuardDecisionRecorder recorder;
    private final ObjectMapper objectMapper;

    public ContestParticipationService(ProblemServiceClient problemServiceClient,
                                       GuardDecisionRecorder recorder,
                                       ObjectMapper objectMapper) {
        this.problemServiceClient = problemServiceClient;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    public record ParticipationView(ParticipantStatus status,
                                    List<RunningContestParticipation> participations,
                                    RunningContestParticipation attributed) {
        public boolean isParticipant() {
            return status != ParticipantStatus.NON_PARTICIPANT;
        }
    }

    public ParticipationView evaluate(long userId, String turnId, String conversationId) {
        return doEvaluate(userId, turnId, conversationId, false);
    }

    /**
     * P3-6 time-race recheck variant (design doc §5.5): bypasses the client's
     * short-TTL guard cache so the pre-return check observes the state at return
     * time, not the turn-start cached value. Same fail-closed contract and audit
     * rows as {@link #evaluate}; the audit detail additionally carries recheck=true.
     */
    public ParticipationView evaluateFresh(long userId, String turnId, String conversationId) {
        return doEvaluate(userId, turnId, conversationId, true);
    }

    private ParticipationView doEvaluate(long userId, String turnId, String conversationId, boolean fresh) {
        long startedNanos = System.nanoTime();
        List<RunningContestParticipation> participations;
        try {
            participations = fresh
                    ? problemServiceClient.runningParticipationsFresh(userId)
                    : problemServiceClient.runningParticipationsStrict(userId);
        } catch (RuntimeException ex) {
            int latencyMs = elapsedMs(startedNanos);
            log.warn("contest participation lookup failed, fail-closed user={} turn={} error={}",
                    userId, turnId, ex.toString());
            recorder.record(turnId, userId, conversationId,
                    GuardLayer.L1_PARTICIPANT, GuardDecision.BLOCK,
                    REASON_LOOKUP_FAILED, markRecheck(detail(null, null, ex.getMessage()), fresh), true, latencyMs);
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                    "比赛状态确认暂时不可用，请稍后重试");
        }
        if (participations == null) {
            participations = List.of();
        }
        int latencyMs = elapsedMs(startedNanos);
        if (participations.isEmpty()) {
            recorder.record(turnId, userId, conversationId,
                    GuardLayer.L1_PARTICIPANT, GuardDecision.PASS,
                    REASON_NON_PARTICIPANT, markRecheck(detail(ParticipantStatus.NON_PARTICIPANT, null, null), fresh),
                    false, latencyMs);
            return new ParticipationView(ParticipantStatus.NON_PARTICIPANT, List.of(), null);
        }
        RunningContestParticipation attributed = attribute(participations);
        boolean inGrace = attributed.endAt() != null && Instant.now().isAfter(attributed.endAt());
        ParticipantStatus status = inGrace ? ParticipantStatus.PARTICIPANT_GRACE : ParticipantStatus.PARTICIPANT_ACTIVE;
        recorder.record(turnId, userId, conversationId,
                GuardLayer.L1_PARTICIPANT, GuardDecision.CONSTRAIN,
                REASON_PARTICIPANT, markRecheck(detail(status, participations, attributed), fresh), false, latencyMs);
        return new ParticipationView(status, participations, attributed);
    }

    private ObjectNode markRecheck(ObjectNode detail, boolean fresh) {
        if (fresh) {
            detail.put("recheck", true);
        }
        return detail;
    }

    /** Wave A attribution: a run still in progress wins; only grace tails otherwise. */
    private RunningContestParticipation attribute(List<RunningContestParticipation> participations) {
        Instant now = Instant.now();
        for (RunningContestParticipation participation : participations) {
            if (participation.endAt() == null || !now.isAfter(participation.endAt())) {
                return participation;
            }
        }
        return participations.get(0);
    }

    private ObjectNode detail(ParticipantStatus status, List<RunningContestParticipation> participations,
                              Object errorOrNull) {
        return detail(status, participations, null, errorOrNull);
    }

    private ObjectNode detail(ParticipantStatus status, List<RunningContestParticipation> participations,
                              RunningContestParticipation attributed) {
        return detail(status, participations, attributed, null);
    }

    private ObjectNode detail(ParticipantStatus status, List<RunningContestParticipation> participations,
                              RunningContestParticipation attributed, Object errorOrNull) {
        ObjectNode detail = objectMapper.createObjectNode();
        if (status != null) {
            detail.put("participantStatus", status.name());
        }
        if (participations != null) {
            ArrayNode runs = detail.putArray("contestRunIds");
            for (RunningContestParticipation participation : participations) {
                runs.add(participation.contestRunId());
            }
        }
        if (attributed != null) {
            detail.put("attributedContestRunId", attributed.contestRunId());
            detail.put("attributedContestId", attributed.contestId());
        }
        if (errorOrNull != null) {
            detail.put("error", String.valueOf(errorOrNull));
        }
        return detail;
    }

    private int elapsedMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
