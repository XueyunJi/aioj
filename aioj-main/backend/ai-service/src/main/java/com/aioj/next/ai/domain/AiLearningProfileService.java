package com.aioj.next.ai.domain;

import com.aioj.next.ai.agent.profile.ProfileAggregateJobProducer;
import com.aioj.next.ai.agent.profile.ProfileSignalIngestionService;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiSubmissionAnalysisEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiSubmissionAnalysisMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.ai.AiLearningProfileEvidenceResponse;
import com.aioj.next.contract.ai.AiLearningProfileResponse;
import com.aioj.next.contract.ai.AiLearningProfileUpdateRequest;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiLearningProfileService {
    private static final Logger log = LoggerFactory.getLogger(AiLearningProfileService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String CATEGORY_WEAKNESS = "weakness";
    private static final String STATE_CANDIDATE = "CANDIDATE";
    private static final String STATE_ACTIVE = "ACTIVE";
    private static final String STATE_RESOLVED = "RESOLVED";
    private static final String STATE_SUPERSEDED = "SUPERSEDED";
    private static final String STATE_DISABLED = "DISABLED";
    private static final String EVIDENCE_TYPE_SUBMISSION_ANALYSIS = "SUBMISSION_ANALYSIS";
    private static final String SOURCE_TYPE_SUBMISSION = "SUBMISSION";
    private static final Set<String> PROFILE_RELATED_RETRIEVAL_OWNER_TYPES = Set.of(
            "learning_profile",
            "profile_evidence",
            "submission_analysis"
    );

    private final AiLearningProfileMapper profileMapper;
    private final AiLearningProfileEvidenceMapper evidenceMapper;
    private final AiSubmissionAnalysisMapper analysisMapper;
    private final ProblemServiceClient problemServiceClient;
    private final AiRetrievalService retrievalService;
    private final AiStructuredSubmissionAnalysisService structuredAnalysisService;
    private final ObjectMapper objectMapper;
    private final ProfileSignalIngestionService profileSignalIngestion;
    private final ProfileAggregateJobProducer profileAggregateProducer;

    public AiLearningProfileService(
            AiLearningProfileMapper profileMapper,
            AiLearningProfileEvidenceMapper evidenceMapper,
            AiSubmissionAnalysisMapper analysisMapper,
            ProblemServiceClient problemServiceClient,
            AiRetrievalService retrievalService,
            AiStructuredSubmissionAnalysisService structuredAnalysisService,
            ObjectMapper objectMapper,
            ProfileSignalIngestionService profileSignalIngestion,
            ProfileAggregateJobProducer profileAggregateProducer
    ) {
        this.profileMapper = profileMapper;
        this.evidenceMapper = evidenceMapper;
        this.analysisMapper = analysisMapper;
        this.problemServiceClient = problemServiceClient;
        this.retrievalService = retrievalService;
        this.structuredAnalysisService = structuredAnalysisService;
        this.objectMapper = objectMapper;
        this.profileSignalIngestion = profileSignalIngestion;
        this.profileAggregateProducer = profileAggregateProducer;
    }

    public record SubmissionAnalysisSignal(
            Long submissionId,
            Long problemId,
            String status,
            String language,
            String codeHash,
            String profileKey,
            List<String> tags,
            boolean masteryEvidence,
            boolean profileEligible,
            String safeSummary,
            Long profileId
    ) {
        public SubmissionAnalysisSignal(
                Long submissionId,
                Long problemId,
                String status,
                String language,
                String codeHash,
                String profileKey,
                List<String> tags,
                String safeSummary,
                Long profileId
        ) {
            this(submissionId, problemId, status, language, codeHash, profileKey, tags, false, true, safeSummary, profileId);
        }
    }

    public record LearningProfileWeakness(
            Long id,
            String profileKey,
            String state,
            String label
    ) {
    }

    public List<AiLearningProfileResponse> list(Long userId, String category, String state) {
        String normalizedState = normalizeOptionalState(state);
        if (STATE_DISABLED.equals(normalizedState)) {
            return List.of();
        }
        QueryWrapper<AiLearningProfileEntity> query = new QueryWrapper<AiLearningProfileEntity>()
                .eq("user_id", userId)
                .isNull("deleted_at")
                .isNull("disabled_at")
                .ne("state", STATE_DISABLED)
                .eq(category != null && !category.isBlank(), "category", category)
                .eq(normalizedState != null, "state", normalizedState)
                .orderByDesc("last_evidence_at")
                .orderByDesc("updated_at");
        return profileMapper.selectList(query).stream()
                .filter(this::isListVisibleProfile)
                .map(profile -> toResponse(profile, evidence(profile.id, 3)))
                .toList();
    }

    public List<AiLearningProfileEvidenceResponse> evidence(Long userId, Long profileId) {
        AiLearningProfileEntity profile = requireProfile(userId, profileId);
        return evidence(profile.id, 50);
    }

    public List<LearningProfileWeakness> recallableWeaknessProfiles(Long userId) {
        return profileMapper.selectList(new QueryWrapper<AiLearningProfileEntity>()
                        .eq("user_id", userId)
                        .eq("category", CATEGORY_WEAKNESS)
                        .isNull("deleted_at")
                        .isNull("disabled_at")
                        .in("state", List.of(STATE_CANDIDATE, STATE_ACTIVE))
                        .orderByDesc("last_evidence_at")
                        .orderByDesc("updated_at")
                        .last("LIMIT 200"))
                .stream()
                .filter(this::isRecallableProfile)
                .map(profile -> new LearningProfileWeakness(profile.id, profile.profileKey, profile.state, profile.label))
                .toList();
    }

    @Transactional
    public void markProfileState(Long userId, Long profileId, String state) {
        AiLearningProfileEntity profile = findProfileById(userId, profileId);
        if (profile == null || !isRecallableProfile(profile)) {
            return;
        }
        String normalizedState = normalizeState(state);
        if (!STATE_RESOLVED.equals(normalizedState) && !STATE_SUPERSEDED.equals(normalizedState)) {
            return;
        }
        profile.state = normalizedState;
        profile.updatedAt = LocalDateTime.now();
        profileMapper.updateById(profile);
        retrievalService.deleteOwner(userId, "learning_profile", String.valueOf(profile.id));
    }

    @Transactional
    public AiLearningProfileResponse update(Long userId, Long profileId, AiLearningProfileUpdateRequest request) {
        AiLearningProfileEntity profile = requireProfile(userId, profileId);
        if (request.label() != null && !request.label().isBlank()) {
            profile.label = request.label().trim();
        }
        String normalizedState = request.state() == null ? null : normalizeState(request.state());
        boolean distrustCleared = false;
        if (request.state() != null && !request.state().isBlank()) {
            profile.state = normalizedState;
            if (STATE_DISABLED.equals(profile.state)) {
                if (profile.disabledAt == null) {
                    profile.disabledAt = LocalDateTime.now();
                }
                // V3 P2-7: user distrust lowers profile confidence (×0.85, mirroring merge
                // WEAKEN semantics) so the AI pays less attention to it.
                if (profile.confidence != null) {
                    profile.confidence = profile.confidence.multiply(new BigDecimal("0.85")).setScale(4, RoundingMode.HALF_UP);
                }
            } else if (STATE_ACTIVE.equals(profile.state) && profile.disabledAt != null) {
                // V3 P2-7: explicit re-activation clears the distrust marker and applies a
                // small confidence boost (+0.1, null treated as 0.5, capped at 1.0). Clearing
                // disabledAt also fixes the legacy gap where list/recall filters kept a
                // re-activated profile hidden forever.
                profile.disabledAt = null;
                double base = profile.confidence == null ? 0.5 : profile.confidence.doubleValue();
                profile.confidence = BigDecimal.valueOf(Math.min(1.0, base + 0.1)).setScale(4, RoundingMode.HALF_UP);
                distrustCleared = true;
            }
        }
        profile.updatedAt = LocalDateTime.now();
        if (distrustCleared) {
            // updateById skips null fields, so the disabled_at clear must go through an
            // explicit UpdateWrapper set(..., null) — otherwise the column stays set in DB.
            profileMapper.update(null, new UpdateWrapper<AiLearningProfileEntity>()
                    .eq("id", profile.id)
                    .set("label", profile.label)
                    .set("state", profile.state)
                    .set("confidence", profile.confidence)
                    .setSql("disabled_at = NULL")
                    .set("updated_at", profile.updatedAt));
        } else {
            profileMapper.updateById(profile);
        }
        return toResponse(profile, evidence(profile.id, 10));
    }

    @Transactional
    public AiLearningProfileResponse markMastered(Long userId, Long profileId) {
        return update(userId, profileId, new AiLearningProfileUpdateRequest("RESOLVED", null, "student marked mastered"));
    }

    @Transactional
    public AiLearningProfileResponse disable(Long userId, Long profileId) {
        AiLearningProfileEntity profile = requireProfile(userId, profileId);
        profile.disabledAt = LocalDateTime.now();
        profile.state = STATE_DISABLED;
        // V3 P2-7: user distrust lowers profile confidence (×0.85, mirroring merge WEAKEN
        // semantics) so the AI pays less attention to it.
        if (profile.confidence != null) {
            profile.confidence = profile.confidence.multiply(new BigDecimal("0.85")).setScale(4, RoundingMode.HALF_UP);
        }
        profile.updatedAt = profile.disabledAt;
        profileMapper.updateById(profile);
        return toResponse(profile, evidence(profile.id, 10));
    }

    @Transactional
    public void delete(Long userId, Long profileId) {
        AiLearningProfileEntity profile = requireProfile(userId, profileId);
        LocalDateTime now = LocalDateTime.now();
        profile.deletedAt = now;
        profile.updatedAt = now;
        profileMapper.updateById(profile);
        retrievalService.deleteOwner(userId, "learning_profile", String.valueOf(profile.id));
        for (AiLearningProfileEvidenceEntity evidence : evidenceMapper.selectList(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                .eq("profile_id", profile.id)
                .last("LIMIT 200"))) {
            if (evidence.id != null) {
                retrievalService.deleteOwner(userId, "profile_evidence", String.valueOf(evidence.id));
            }
        }
    }

    @Transactional
    public SubmissionAnalysisSignal recordSubmissionAnalysis(Long userId, AiChatRequest request, AiCompletion completion, Long assistantMessageId) {
        AiChatRequest.SubmissionContext selected = request.submissionContext();
        if (selected == null || selected.submissionId() == null) {
            return null;
        }
        AiChatRequest.ContestContext contest = request.contestContext();
        AiSubmissionContextResponse context = problemServiceClient.aiSubmissionContext(new AiSubmissionContextRequest(
                userId,
                selected.submissionId(),
                request.problemId(),
                contest == null ? null : contest.contestId(),
                contest == null ? null : contest.contestRunId(),
                contest == null ? null : contest.contestProblemId(),
                "AI_SUBMISSION_ANALYSIS_RECORD"
        ));
        return recordSubmissionAnalysisFromContext(userId, request, completion, assistantMessageId, context, false);
    }

    @Transactional
    public SubmissionAnalysisSignal recordJudgedSubmissionAnalysis(AiJudgedSubmissionEventRequest event) {
        if (event == null || event.userId() == null || event.submissionId() == null || event.problemId() == null) {
            return null;
        }
        AiSubmissionContextResponse context = problemServiceClient.aiSubmissionContext(new AiSubmissionContextRequest(
                event.userId(),
                event.submissionId(),
                event.problemId(),
                event.contestId(),
                event.contestRunId(),
                event.contestProblemId(),
                "AI_JUDGED_SUBMISSION_ANALYSIS_RECORD"
        ));
        AiSubmissionAnalysisEntity existingManualAnalysis = analysisMapper.selectOne(new QueryWrapper<AiSubmissionAnalysisEntity>()
                .eq("user_id", event.userId())
                .eq("submission_id", context.submissionId())
                .isNotNull("ai_message_id")
                .last("LIMIT 1"));
        if (existingManualAnalysis != null) {
            return new SubmissionAnalysisSignal(
                    context.submissionId(),
                    context.problemId(),
                    context.status(),
                    context.language(),
                    context.codeHash(),
                    null,
                    List.of(),
                    false,
                    false,
                    existingManualAnalysis.summary,
                    null
            );
        }
        assertSafeAutoContext(context);
        AiChatRequest request = new AiChatRequest(
                "judged-submission:" + context.submissionId(),
                context.problemId(),
                "后台自动分析判题完成提交。",
                "assist",
                null,
                null,
                null,
                null,
                null,
                hasContestContext(context)
                        ? new AiChatRequest.ContestContext(context.contestId(), context.contestRunId(), context.contestProblemId())
                        : null,
                new AiChatRequest.SubmissionContext(context.submissionId(), "AI_JUDGED_SUBMISSION_ANALYSIS", false, null)
        );
        AiCompletion completion = new AiCompletion("后台自动分析判题完成提交。", "judged-submission", "background-analysis", 0, 0);
        return recordSubmissionAnalysisFromContext(event.userId(), request, completion, null, context, true);
    }

    private SubmissionAnalysisSignal recordSubmissionAnalysisFromContext(
            Long userId,
            AiChatRequest request,
            AiCompletion completion,
            Long assistantMessageId,
            AiSubmissionContextResponse context,
            boolean automaticJudgedSubmission
    ) {
        AiStructuredSubmissionAnalysisService.AnalysisResult structured =
                structuredAnalysisService.analyze(userId, request, completion, context);
        String profileKey = structured.profileKey();
        String label = structured.profileLabel();
        String summary = analysisSummary(context, structured);
        List<String> tags = structured.signalTags();
        LocalDateTime now = LocalDateTime.now();

        AiSubmissionAnalysisEntity analysis = new AiSubmissionAnalysisEntity();
        analysis.userId = userId;
        analysis.submissionId = context.submissionId();
        analysis.problemId = context.problemId();
        analysis.contestId = context.contestId();
        analysis.contestRunId = context.contestRunId();
        analysis.contestProblemId = context.contestProblemId();
        analysis.status = context.status();
        analysis.language = context.language();
        analysis.codeHash = context.codeHash();
        analysis.rootCauseTags = toJson(structured.flatTags());
        analysis.summary = summary;
        analysis.aiMessageId = assistantMessageId;
        analysis.confidence = BigDecimal.valueOf(structured.confidence());
        analysis.createdAt = now;
        AiSubmissionAnalysisEntity existingAnalysis = analysisMapper.selectOne(new QueryWrapper<AiSubmissionAnalysisEntity>()
                .eq("user_id", userId)
                .eq("submission_id", context.submissionId())
                .last("LIMIT 1"));
        if (existingAnalysis == null) {
            analysisMapper.insert(analysis);
        } else {
            if (automaticJudgedSubmission && existingAnalysis.aiMessageId != null) {
                return new SubmissionAnalysisSignal(
                        context.submissionId(),
                        context.problemId(),
                        context.status(),
                        context.language(),
                        context.codeHash(),
                        profileKey,
                        tags,
                        structured.masteryEvidence(),
                        structured.profileEligible(),
                        existingAnalysis.summary,
                        null
                );
            }
            analysis.id = existingAnalysis.id;
            analysisMapper.updateById(analysis);
        }

        // Agent Core V3 P2-6: judged-submission runs also feed the ai_profile_signals
        // sidecar. Only the judged path (automaticJudgedSubmission=true, i.e.
        // recordJudgedSubmissionAnalysis) derives signals; the chat path is untouched.
        // The early returns above (pre-existing manual analysis) skip this, keeping the
        // (source_type, source_id) idempotency semantics clean.
        if (automaticJudgedSubmission) {
            recordJudgedSubmissionSignals(userId, context, structured);
        }

        AiLearningProfileEntity profile = structured.profileEligible() ? upsertProfile(userId, profileKey, label, now, structured.confidence()) : null;
        AiRetrievalService.AiRetrievalChunkMetadata retrievalMetadata = retrievalMetadata(context, structured, label);
        String analysisOwnerId = ownerId(analysis.id, context.submissionId());
        retrievalService.deleteOwner(userId, "submission_analysis", analysisOwnerId);
        retrievalService.indexChunk(
                userId,
                "submission_analysis",
                analysisOwnerId,
                summary,
                retrievalMetadata
        );
        if (profile == null) {
            return new SubmissionAnalysisSignal(
                    context.submissionId(),
                    context.problemId(),
                    context.status(),
                    context.language(),
                    context.codeHash(),
                    profileKey,
                    tags,
                    structured.masteryEvidence(),
                    structured.profileEligible(),
                    summary,
                    null
            );
        }
        AiLearningProfileEvidenceEntity evidence = new AiLearningProfileEvidenceEntity();
        evidence.userId = userId;
        evidence.profileId = profile.id;
        evidence.evidenceType = EVIDENCE_TYPE_SUBMISSION_ANALYSIS;
        evidence.sourceType = SOURCE_TYPE_SUBMISSION;
        evidence.sourceId = String.valueOf(context.submissionId());
        evidence.summary = summary;
        evidence.confidence = BigDecimal.valueOf(structured.confidence());
        evidence.codeHash = context.codeHash();
        evidence.createdAt = now;
        AiLearningProfileEvidenceEntity existingEvidence = evidenceMapper.selectOne(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                .eq("profile_id", profile.id)
                .eq("evidence_type", EVIDENCE_TYPE_SUBMISSION_ANALYSIS)
                .eq("source_type", SOURCE_TYPE_SUBMISSION)
                .eq("source_id", String.valueOf(context.submissionId()))
                .last("LIMIT 1"));
        if (existingEvidence == null) {
            evidenceMapper.insert(evidence);
        } else {
            evidence.id = existingEvidence.id;
            evidence.createdAt = existingEvidence.createdAt == null ? now : existingEvidence.createdAt;
            evidenceMapper.updateById(evidence);
        }
        String evidenceOwnerId = ownerId(evidence.id, context.submissionId());
        retrievalService.deleteOwner(userId, "profile_evidence", evidenceOwnerId);
        retrievalService.indexChunk(
                userId,
                "profile_evidence",
                evidenceOwnerId,
                summary,
                retrievalMetadata
        );

        syncEvidenceStats(profile);
        profile.updatedAt = now;
        profileMapper.updateById(profile);
        retrievalService.deleteOwner(userId, "learning_profile", String.valueOf(profile.id));
        retrievalService.indexChunk(userId, "learning_profile", String.valueOf(profile.id), label + "\n" + summary, retrievalMetadata);
        return new SubmissionAnalysisSignal(
                context.submissionId(),
                context.problemId(),
                context.status(),
                context.language(),
                context.codeHash(),
                profileKey,
                tags,
                structured.masteryEvidence(),
                structured.profileEligible(),
                summary,
                profile.id
        );
    }

    private boolean hasContestContext(AiSubmissionContextResponse context) {
        return context.contestId() != null || context.contestRunId() != null || context.contestProblemId() != null;
    }

    /**
     * Agent Core V3 P2-6 sidecar: derive at most one learning-profile signal from the
     * judged-submission analysis, persist it into ai_profile_signals, then schedule the
     * PROFILE_AGGREGATE job. Fully best-effort — any failure is logged and never affects
     * the judged-analysis main flow.
     */
    private void recordJudgedSubmissionSignals(
            Long userId,
            AiSubmissionContextResponse context,
            AiStructuredSubmissionAnalysisService.AnalysisResult structured
    ) {
        try {
            List<ProfileSignalIngestionService.SignalProposal> proposals = deriveJudgedSubmissionSignals(context, structured);
            if (proposals.isEmpty()) {
                return;
            }
            int inserted = profileSignalIngestion.recordJudgedSubmissionSignals(userId, context.submissionId(), proposals);
            if (inserted > 0) {
                profileAggregateProducer.enqueueForUser(userId);
            }
        } catch (Exception ex) {
            log.warn("judged submission profile signal sidecar failed userId={} submissionId={} error={}",
                    userId, context.submissionId(), ex.toString());
        }
    }

    private List<ProfileSignalIngestionService.SignalProposal> deriveJudgedSubmissionSignals(
            AiSubmissionContextResponse context,
            AiStructuredSubmissionAnalysisService.AnalysisResult structured
    ) {
        String title = context.problemContext() == null ? null : context.problemContext().title();
        String displayTitle = title == null || title.isBlank() ? "problem " + context.problemId() : title.trim();
        String knowledgeNode = judgedSignalKnowledgeNode(context, structured);
        if (structured.profileEligible()) {
            String statusLabel = context.status() == null ? "unknown" : context.status().toLowerCase(Locale.ROOT);
            StringBuilder signal = new StringBuilder(statusLabel).append(" on「").append(displayTitle).append("」");
            List<String> rootCauseTags = structured.rootCauseTags();
            if (rootCauseTags != null && !rootCauseTags.isEmpty()) {
                signal.append(": ").append(String.join(", ", rootCauseTags.subList(0, Math.min(2, rootCauseTags.size()))));
            }
            return List.of(new ProfileSignalIngestionService.SignalProposal(
                    signal.toString(), "WEAKNESS", knowledgeNode, "NEGATIVE", structured.confidence()));
        }
        if ("ACCEPTED".equals(context.status()) && structured.masteryEvidence()) {
            return List.of(new ProfileSignalIngestionService.SignalProposal(
                    "accepted on「" + displayTitle + "」", "MASTERY", knowledgeNode, "POSITIVE", structured.confidence()));
        }
        return List.of();
    }

    /** primaryTag, else the profileKey subject with the status prefix stripped, else "general". */
    private String judgedSignalKnowledgeNode(
            AiSubmissionContextResponse context,
            AiStructuredSubmissionAnalysisService.AnalysisResult structured
    ) {
        String tag = primaryTag(context, structured);
        if (tag != null && !tag.isBlank()) {
            return tag.trim();
        }
        String profileKey = structured.profileKey();
        if (profileKey != null && !profileKey.isBlank()) {
            String prefix = judgedStatusKey(context.status()) + "_";
            if (profileKey.startsWith(prefix) && profileKey.length() > prefix.length()) {
                return profileKey.substring(prefix.length());
            }
        }
        return "general";
    }

    /** Mirrors AiStructuredSubmissionAnalysisService.statusKey (normalizeTag over the status). */
    private String judgedStatusKey(String status) {
        String normalized = (status == null || status.isBlank() ? "unknown" : status.trim())
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    private void assertSafeAutoContext(AiSubmissionContextResponse context) {
        if (context != null && context.contestActive()
                && (context.codeText() != null || context.stdoutExcerpt() != null || context.stderrExcerpt() != null)) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Active contest AI submission context contains unsafe fields");
        }
    }

    private AiLearningProfileEntity upsertProfile(Long userId, String profileKey, String label, LocalDateTime now, double confidence) {
        AiLearningProfileEntity existing = profileMapper.selectOne(new QueryWrapper<AiLearningProfileEntity>()
                .eq("user_id", userId)
                .eq("category", CATEGORY_WEAKNESS)
                .eq("profile_key", profileKey)
                .isNull("deleted_at")
                .last("LIMIT 1"));
        if (existing != null) {
            if (isDisabledProfile(existing)) {
                return null;
            }
            if (STATE_RESOLVED.equalsIgnoreCase(existing.state) || STATE_SUPERSEDED.equalsIgnoreCase(existing.state)) {
                return null;
            }
            existing.state = normalizeProfileStateOrDefault(existing.state, STATE_CANDIDATE);
            existing.label = label;
            existing.confidence = BigDecimal.valueOf(confidence);
            existing.updatedAt = now;
            profileMapper.updateById(existing);
            return existing;
        }
        AiLearningProfileEntity created = new AiLearningProfileEntity();
        created.userId = userId;
        created.category = CATEGORY_WEAKNESS;
        created.profileKey = profileKey;
        created.label = label;
        created.state = STATE_CANDIDATE;
        created.confidence = BigDecimal.valueOf(confidence);
        created.evidenceCount = 0;
        created.createdAt = now;
        created.updatedAt = now;
        profileMapper.insert(created);
        return created;
    }

    public List<AiRetrievalService.AiRetrievalHit> filterRecallableRetrievalHits(
            Long userId,
            List<AiRetrievalService.AiRetrievalHit> hits
    ) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<AiRetrievalService.AiRetrievalHit> filtered = new ArrayList<>();
        Set<String> deniedProfileKeys = new HashSet<>();
        Set<String> allowedProfileKeys = new HashSet<>();
        for (AiRetrievalService.AiRetrievalHit hit : hits) {
            if (!isProfileRelatedHit(hit)) {
                filtered.add(hit);
                continue;
            }
            if ("learning_profile".equals(hit.ownerType())) {
                AiLearningProfileEntity profile = findProfileById(userId, parseLong(hit.ownerId()));
                if (isRecallableProfile(profile)) {
                    filtered.add(hit);
                }
                continue;
            }
            String profileKey = profileKeyForHit(userId, hit);
            if (profileKey == null || profileKey.isBlank()) {
                continue;
            }
            if (deniedProfileKeys.contains(profileKey)) {
                continue;
            }
            if (allowedProfileKeys.contains(profileKey)) {
                filtered.add(hit);
                continue;
            }
            AiLearningProfileEntity profile = findProfileByKey(userId, profileKey);
            if (isRecallableProfile(profile)) {
                allowedProfileKeys.add(profileKey);
                filtered.add(hit);
            } else {
                deniedProfileKeys.add(profileKey);
            }
        }
        return filtered;
    }

    private AiLearningProfileEntity requireProfile(Long userId, Long profileId) {
        AiLearningProfileEntity profile = profileMapper.selectOne(new QueryWrapper<AiLearningProfileEntity>()
                .eq("id", profileId)
                .eq("user_id", userId)
                .isNull("deleted_at")
                .last("LIMIT 1"));
        if (profile == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Learning profile not found");
        }
        return profile;
    }

    private List<AiLearningProfileEvidenceResponse> evidence(Long profileId, int limit) {
        return evidenceMapper.selectList(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                        .eq("profile_id", profileId)
                        .orderByDesc("created_at")
                        .last("LIMIT " + Math.max(1, Math.min(limit, 100))))
                .stream()
                .map(this::toEvidenceResponse)
                .toList();
    }

    private AiLearningProfileResponse toResponse(AiLearningProfileEntity profile,
                                                 List<AiLearningProfileEvidenceResponse> evidence) {
        return new AiLearningProfileResponse(
                String.valueOf(profile.id),
                profile.category,
                profile.profileKey,
                profile.label,
                profile.state,
                profile.confidence == null ? null : profile.confidence.doubleValue(),
                profile.evidenceCount,
                toInstant(profile.lastEvidenceAt),
                toInstant(profile.createdAt),
                toInstant(profile.updatedAt),
                evidence
        );
    }

    private AiLearningProfileEvidenceResponse toEvidenceResponse(AiLearningProfileEvidenceEntity evidence) {
        return new AiLearningProfileEvidenceResponse(
                String.valueOf(evidence.id),
                String.valueOf(evidence.profileId),
                evidence.evidenceType,
                evidence.sourceType,
                evidence.sourceId,
                evidence.summary,
                evidence.confidence == null ? null : evidence.confidence.doubleValue(),
                toInstant(evidence.createdAt)
        );
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private String normalizeState(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (List.of(STATE_CANDIDATE, STATE_ACTIVE, STATE_RESOLVED, STATE_SUPERSEDED, STATE_DISABLED).contains(normalized)) {
            return normalized;
        }
        throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported learning profile state");
    }

    private String normalizeOptionalState(String value) {
        return value == null || value.isBlank() ? null : normalizeState(value);
    }

    private String analysisSummary(
            AiSubmissionContextResponse context,
            AiStructuredSubmissionAnalysisService.AnalysisResult structured
    ) {
        return """
                submission=%s
                status=%s
                language=%s
                codeHash=%s
                rootCauseTags=%s
                algorithmTags=%s
                bugPatternTags=%s
                complexityTags=%s
                masteryEvidence=%s
                profileEligible=%s
                structuredSummary=%s
                nextSteps=%s
                evidenceItems=%s
                judgeMessage=%s
                """.formatted(
                context.submissionId(),
                context.status(),
                context.language(),
                safeScalar(context.codeHash(), 128),
                String.join(",", structured.rootCauseTags()),
                String.join(",", structured.algorithmTags()),
                String.join(",", structured.bugPatternTags()),
                String.join(",", structured.complexityTags()),
                structured.masteryEvidence(),
                structured.profileEligible(),
                evidenceSafeText(structured.summary(), 700),
                safeJoined(structured.nextSteps(), 700),
                safeJoined(structured.evidenceItems(), 700),
                evidenceSafeText(context.judgeMessage(), 400)
        ).trim();
    }

    private AiRetrievalService.AiRetrievalChunkMetadata retrievalMetadata(
            AiSubmissionContextResponse context,
            AiStructuredSubmissionAnalysisService.AnalysisResult structured,
            String label
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("source", "submission_analysis");
        attributes.put("status", context.status());
        attributes.put("language", context.language());
        attributes.put("codeHash", context.codeHash());
        attributes.put("label", label);
        attributes.put("profileKey", structured.profileKey());
        attributes.put("rootCauseTags", structured.rootCauseTags());
        attributes.put("algorithmTags", structured.algorithmTags());
        attributes.put("bugPatternTags", structured.bugPatternTags());
        attributes.put("complexityTags", structured.complexityTags());
        attributes.put("masteryEvidence", structured.masteryEvidence());
        attributes.put("profileEligible", structured.profileEligible());
        attributes.put("confidence", structured.confidence());
        return AiRetrievalService.AiRetrievalChunkMetadata.of(
                context.problemId(),
                context.submissionId(),
                context.contestId(),
                context.contestRunId(),
                context.contestProblemId(),
                primaryTag(context, structured),
                structured.profileKey(),
                attributes
        );
    }

    private String primaryTag(AiSubmissionContextResponse context, AiStructuredSubmissionAnalysisService.AnalysisResult structured) {
        if (structured.algorithmTags() != null && !structured.algorithmTags().isEmpty()) {
            return structured.algorithmTags().get(0);
        }
        if (context.problemContext() == null || context.problemContext().tags() == null || context.problemContext().tags().isEmpty()) {
            return null;
        }
        return context.problemContext().tags().get(0);
    }

    private String safeJoined(List<String> values, int maxLength) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return evidenceSafeText(String.join("; ", values), maxLength);
    }

    private String ownerId(Long primary, Long fallback) {
        return String.valueOf(primary == null ? fallback : primary);
    }

    private String evidenceSafeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = redactSecretLikeText(value)
                .replaceAll("(?i)\"(codeText|stdoutExcerpt|stderrExcerpt)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"", "\"$1\":\"[omitted]\"")
                .replaceAll("(?i)(codeText|stdoutExcerpt|stderrExcerpt)\\s*[:=].*", "$1=[omitted]");
        StringBuilder kept = new StringBuilder();
        boolean inCodeFence = false;
        boolean inRawOutputBlock = false;
        int omittedLines = 0;
        int omittedRawOutputLines = 0;
        int omittedSecretLines = 0;
        for (String rawLine : normalized.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence;
                omittedLines++;
                continue;
            }
            if (isRawOutputLabel(line)) {
                inRawOutputBlock = true;
                omittedRawOutputLines++;
                continue;
            }
            if (inRawOutputBlock) {
                if (line.isBlank() || isSummaryBoundary(line)) {
                    inRawOutputBlock = false;
                } else {
                    omittedRawOutputLines++;
                    continue;
                }
            }
            if (containsSecretLikeText(line)) {
                omittedSecretLines++;
                continue;
            }
            if (inCodeFence || looksLikeCodeLine(line)) {
                omittedLines++;
                continue;
            }
            if (!line.isBlank()) {
                if (!kept.isEmpty()) {
                    kept.append('\n');
                }
                kept.append(line);
            }
        }
        if (omittedLines > 0) {
            if (!kept.isEmpty()) {
                kept.append('\n');
            }
            kept.append("[submit-ready code omitted from learning evidence]");
        }
        if (omittedRawOutputLines > 0) {
            if (!kept.isEmpty()) {
                kept.append('\n');
            }
            kept.append("[raw output omitted from learning evidence]");
        }
        if (omittedSecretLines > 0) {
            if (!kept.isEmpty()) {
                kept.append('\n');
            }
            kept.append("[secret-like text omitted from learning evidence]");
        }
        return truncate(kept.toString(), maxLength);
    }

    private boolean looksLikeCodeLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("#include")
                || lower.startsWith("using namespace")
                || lower.startsWith("public class")
                || lower.startsWith("public static void main")
                || lower.startsWith("class solution")
                || lower.startsWith("def main")
                || lower.startsWith("import sys")
                || lower.startsWith("from sys")
                || lower.startsWith("if __name__")
                || lower.contains("scanner")
                || lower.contains("sys.stdin")
                || lower.contains("cin >>")
                || lower.contains("cout <<")
                || lower.contains("int main(")
                || lower.matches(".*[{};]\\s*$");
    }

    private boolean isRawOutputLabel(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("stdoutexcerpt:")
                || lower.startsWith("stderrexcerpt:")
                || lower.startsWith("stdout:")
                || lower.startsWith("stderr:");
    }

    private boolean isSummaryBoundary(String line) {
        return line != null && (line.startsWith("#") || line.startsWith("- ") || line.startsWith("* ") || line.matches("^[A-Za-z][A-Za-z0-9 _-]{0,40}:.*"));
    }

    private boolean containsSecretLikeText(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("sk-")
                || lower.contains("openai_api_key")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("bearer ")
                || lower.matches(".*\\b(token|password|secret)\\b.*[:=].+");
    }

    private String redactSecretLikeText(String value) {
        return value
                .replaceAll("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*\\S+", "$1=***")
                .replaceAll("(?i)sk-[a-z0-9_\\-]{4,}", "sk-***")
                .replaceAll("(?i)(bearer)\\s+[a-z0-9._\\-]{8,}", "$1 ***");
    }

    private String safeScalar(String value, int maxLength) {
        return evidenceSafeText(value, maxLength).replace("\n", " ");
    }

    private void syncEvidenceStats(AiLearningProfileEntity profile) {
        Long count = evidenceMapper.selectCount(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                .eq("profile_id", profile.id));
        profile.evidenceCount = count == null ? 0 : (int) Math.min(Integer.MAX_VALUE, count);
        AiLearningProfileEvidenceEntity latest = evidenceMapper.selectOne(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                .eq("profile_id", profile.id)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        profile.lastEvidenceAt = latest == null ? null : latest.createdAt;
    }

    private boolean isListVisibleProfile(AiLearningProfileEntity profile) {
        return profile != null
                && profile.deletedAt == null
                && profile.disabledAt == null
                && !STATE_DISABLED.equalsIgnoreCase(profile.state);
    }

    private boolean isDisabledProfile(AiLearningProfileEntity profile) {
        return profile != null && (profile.disabledAt != null || STATE_DISABLED.equalsIgnoreCase(profile.state));
    }

    private boolean isRecallableProfile(AiLearningProfileEntity profile) {
        if (profile == null || profile.deletedAt != null || profile.disabledAt != null) {
            return false;
        }
        return STATE_CANDIDATE.equalsIgnoreCase(profile.state) || STATE_ACTIVE.equalsIgnoreCase(profile.state);
    }

    private boolean isProfileRelatedHit(AiRetrievalService.AiRetrievalHit hit) {
        return hit != null && PROFILE_RELATED_RETRIEVAL_OWNER_TYPES.contains(hit.ownerType());
    }

    private String profileKeyForHit(Long userId, AiRetrievalService.AiRetrievalHit hit) {
        if (hit == null) {
            return null;
        }
        if (hit.profileKey() != null && !hit.profileKey().isBlank()) {
            return hit.profileKey();
        }
        Object profileKey = hit.metadata() == null ? null : hit.metadata().get("profileKey");
        return profileKey == null ? null : profileKey.toString();
    }

    private AiLearningProfileEntity findProfileById(Long userId, Long profileId) {
        if (userId == null || profileId == null) {
            return null;
        }
        return profileMapper.selectOne(new QueryWrapper<AiLearningProfileEntity>()
                .eq("id", profileId)
                .eq("user_id", userId)
                .isNull("deleted_at")
                .last("LIMIT 1"));
    }

    private AiLearningProfileEntity findProfileByKey(Long userId, String profileKey) {
        if (userId == null || profileKey == null || profileKey.isBlank()) {
            return null;
        }
        return profileMapper.selectOne(new QueryWrapper<AiLearningProfileEntity>()
                .eq("user_id", userId)
                .eq("category", CATEGORY_WEAKNESS)
                .eq("profile_key", profileKey)
                .isNull("deleted_at")
                .last("LIMIT 1"));
    }

    private String normalizeProfileStateOrDefault(String state, String fallback) {
        if (state == null || state.isBlank()) {
            return fallback;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return List.of(STATE_CANDIDATE, STATE_ACTIVE, STATE_RESOLVED, STATE_SUPERSEDED, STATE_DISABLED).contains(normalized)
                ? normalized
                : fallback;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }
}
