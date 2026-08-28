package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.OperationAuditWriter;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiSubmissionAnalysisEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiSubmissionAnalysisMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiMemoryMarkdownArchiveService {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> PENDING_CANDIDATE_STATUSES = Set.of(
            "CANDIDATE",
            "NEEDS_CONFIRMATION",
            "AWAITING_CLARIFICATION"
    );

    private final AiUserMemoryMapper memoryMapper;
    private final AiLearningProfileMapper profileMapper;
    private final AiLearningProfileEvidenceMapper profileEvidenceMapper;
    private final AiSubmissionAnalysisMapper submissionAnalysisMapper;
    private final AiMemoryCandidateMapper candidateMapper;
    private final AiMemoryEvidenceMapper memoryEvidenceMapper;
    private final AiMemoryEventPayloadSanitizer sanitizer;
    private final OperationAuditWriter auditWriter;

    public AiMemoryMarkdownArchiveService(
            AiUserMemoryMapper memoryMapper,
            AiLearningProfileMapper profileMapper,
            AiLearningProfileEvidenceMapper profileEvidenceMapper,
            AiSubmissionAnalysisMapper submissionAnalysisMapper,
            AiMemoryCandidateMapper candidateMapper,
            AiMemoryEvidenceMapper memoryEvidenceMapper,
            AiMemoryEventPayloadSanitizer sanitizer,
            OperationAuditWriter auditWriter
    ) {
        this.memoryMapper = memoryMapper;
        this.profileMapper = profileMapper;
        this.profileEvidenceMapper = profileEvidenceMapper;
        this.submissionAnalysisMapper = submissionAnalysisMapper;
        this.candidateMapper = candidateMapper;
        this.memoryEvidenceMapper = memoryEvidenceMapper;
        this.sanitizer = sanitizer;
        this.auditWriter = auditWriter;
    }

    public MarkdownArchive export(Long userId) {
        LocalDateTime generatedAt = LocalDateTime.now();
        List<AiUserMemoryEntity> memories = memories(userId);
        List<AiUserMemoryEntity> currentMemories = memories.stream()
                .filter(this::isCurrentMemory)
                .toList();
        List<AiUserMemoryEntity> memoryHistory = memories.stream()
                .filter(memory -> !isCurrentMemory(memory))
                .toList();
        List<AiLearningProfileEntity> profiles = profiles(userId);
        List<AiLearningProfileEvidenceEntity> profileEvidence = profileEvidence(userId, profiles);
        List<AiSubmissionAnalysisEntity> analyses = submissionAnalyses(userId);
        List<AiMemoryCandidateEntity> candidates = pendingCandidates(userId);
        List<AiMemoryEvidenceEntity> memoryEvidence = memoryEvidence(userId);
        ArchiveCounts counts = new ArchiveCounts(
                currentMemories.size(),
                memoryHistory.size(),
                profiles.size(),
                profileEvidence.size(),
                analyses.size(),
                candidates.size(),
                memoryEvidence.size()
        );
        String fileName = "aioj-learning-archive-" + FILE_TIME.format(generatedAt) + ".md";
        String markdown = markdown(userId, generatedAt, currentMemories, memoryHistory, profiles, profileEvidence, analyses, candidates, memoryEvidence, counts);
        auditWriter.record(
                "AI_MEMORY_MARKDOWN_EXPORT",
                "AI_MEMORY_ARCHIVE",
                null,
                "SUCCESS",
                Map.of(
                        "fileName", fileName,
                        "generatedAt", DISPLAY_TIME.format(generatedAt),
                        "memoryCount", counts.memoryCount(),
                        "memoryHistoryCount", counts.memoryHistoryCount(),
                        "learningProfileCount", counts.learningProfileCount(),
                        "profileEvidenceCount", counts.profileEvidenceCount(),
                        "submissionAnalysisCount", counts.submissionAnalysisCount(),
                        "pendingCandidateCount", counts.pendingCandidateCount(),
                        "memoryEvidenceCount", counts.memoryEvidenceCount()
                ),
                userId,
                null,
                null,
                userId
        );
        return new MarkdownArchive(fileName, markdown, counts);
    }

    private List<AiUserMemoryEntity> memories(Long userId) {
        return memoryMapper.selectList(new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", userId)
                .ne("status", AiMemoryService.STATUS_CANDIDATE)
                .orderByAsc("category")
                .orderByDesc("updated_at")
                .last("LIMIT 500"));
    }

    private List<AiLearningProfileEntity> profiles(Long userId) {
        return profileMapper.selectList(new QueryWrapper<AiLearningProfileEntity>()
                .eq("user_id", userId)
                .isNull("deleted_at")
                .orderByAsc("category")
                .orderByDesc("last_evidence_at")
                .orderByDesc("updated_at")
                .last("LIMIT 500"));
    }

    private List<AiLearningProfileEvidenceEntity> profileEvidence(Long userId, List<AiLearningProfileEntity> profiles) {
        List<Long> profileIds = profiles.stream()
                .map(profile -> profile.id)
                .filter(id -> id != null)
                .toList();
        if (profileIds.isEmpty()) {
            return List.of();
        }
        return profileEvidenceMapper.selectList(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                .eq("user_id", userId)
                .in("profile_id", profileIds)
                .orderByDesc("created_at")
                .last("LIMIT 1000"));
    }

    private List<AiSubmissionAnalysisEntity> submissionAnalyses(Long userId) {
        return submissionAnalysisMapper.selectList(new QueryWrapper<AiSubmissionAnalysisEntity>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .last("LIMIT 500"));
    }

    private List<AiMemoryCandidateEntity> pendingCandidates(Long userId) {
        return candidateMapper.selectList(new QueryWrapper<AiMemoryCandidateEntity>()
                .eq("user_id", userId)
                .in("status", PENDING_CANDIDATE_STATUSES)
                .orderByDesc("updated_at")
                .last("LIMIT 500"));
    }

    private List<AiMemoryEvidenceEntity> memoryEvidence(Long userId) {
        return memoryEvidenceMapper.selectList(new QueryWrapper<AiMemoryEvidenceEntity>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .last("LIMIT 1000"));
    }

    private String markdown(
            Long userId,
            LocalDateTime generatedAt,
            List<AiUserMemoryEntity> currentMemories,
            List<AiUserMemoryEntity> memoryHistory,
            List<AiLearningProfileEntity> profiles,
            List<AiLearningProfileEvidenceEntity> profileEvidence,
            List<AiSubmissionAnalysisEntity> analyses,
            List<AiMemoryCandidateEntity> candidates,
            List<AiMemoryEvidenceEntity> memoryEvidence,
            ArchiveCounts counts
    ) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# AI-OJ AI 学习档案\n\n");
        markdown.append("> 这是只读导出文件，仅包含安全摘要、状态和定位 ID；不会导出源码、原始输出、Prompt 或密钥。\n\n");
        appendMetadata(markdown, userId, generatedAt, counts);
        appendCurrentMemories(markdown, currentMemories);
        appendMemoryHistory(markdown, memoryHistory);
        appendProfiles(markdown, profiles, profileEvidence);
        appendSubmissionAnalyses(markdown, analyses);
        appendCandidates(markdown, candidates);
        appendMemoryEvidence(markdown, memoryEvidence);
        return markdown.toString();
    }

    private void appendMetadata(StringBuilder markdown, Long userId, LocalDateTime generatedAt, ArchiveCounts counts) {
        markdown.append("## 元数据\n\n");
        markdown.append("- 用户 ID：").append(userId).append('\n');
        markdown.append("- 导出时间：").append(DISPLAY_TIME.format(generatedAt)).append('\n');
        markdown.append("- 当前可召回长期记忆：").append(counts.memoryCount()).append(" 条\n");
        markdown.append("- 合并与停用历史记忆：").append(counts.memoryHistoryCount()).append(" 条\n");
        markdown.append("- 学习画像：").append(counts.learningProfileCount()).append(" 条\n");
        markdown.append("- 画像证据：").append(counts.profileEvidenceCount()).append(" 条\n");
        markdown.append("- 提交分析：").append(counts.submissionAnalysisCount()).append(" 条\n");
        markdown.append("- 待确认候选：").append(counts.pendingCandidateCount()).append(" 条\n");
        markdown.append("- 长期记忆证据：").append(counts.memoryEvidenceCount()).append(" 条\n\n");
    }

    private boolean isCurrentMemory(AiUserMemoryEntity memory) {
        return memory != null && AiMemoryService.STATUS_ACTIVE.equals(statusLabel(memory.getStatus()));
    }

    private void appendCurrentMemories(StringBuilder markdown, List<AiUserMemoryEntity> memories) {
        markdown.append("## 当前可召回长期记忆\n\n");
        if (memories.isEmpty()) {
            markdown.append("暂无当前可召回长期记忆。\n\n");
            return;
        }
        appendMemoryEntries(markdown, memories);
    }

    private void appendMemoryHistory(StringBuilder markdown, List<AiUserMemoryEntity> memories) {
        markdown.append("## 合并与停用历史\n\n");
        if (memories.isEmpty()) {
            markdown.append("暂无合并或停用历史。\n\n");
            return;
        }
        markdown.append("> 本章节保留证据链；这些记忆不会进入后续召回。\n\n");
        appendMemoryEntries(markdown, memories);
    }

    private void appendMemoryEntries(StringBuilder markdown, List<AiUserMemoryEntity> memories) {
        String currentCategory = "";
        for (AiUserMemoryEntity memory : memories) {
            String category = safeInline(memory.getCategory());
            if (!category.equals(currentCategory)) {
                currentCategory = category;
                markdown.append("### ").append(category.isBlank() ? "memory" : category).append("\n\n");
            }
            markdown.append("- #").append(memory.getId())
                    .append(" ").append(safeInline(firstNonBlank(memory.getTitle(), memory.getMemoryType(), "未命名记忆")))
                    .append('\n');
            appendBullet(markdown, "状态", statusLabel(memory.getStatus()));
            appendBullet(markdown, "类型", memory.getMemoryType());
            appendBullet(markdown, "来源", memory.getSource());
            appendBullet(markdown, "置信度", formatDecimal(memory.getConfidence()));
            appendBullet(markdown, "更新时间", formatTime(memory.getUpdatedAt()));
            appendBullet(markdown, "召回状态", recallState(memory.getStatus()));
            appendBlock(markdown, "摘要", memory.getContent());
            markdown.append('\n');
        }
    }

    private void appendProfiles(
            StringBuilder markdown,
            List<AiLearningProfileEntity> profiles,
            List<AiLearningProfileEvidenceEntity> evidence
    ) {
        markdown.append("## 学习画像\n\n");
        if (profiles.isEmpty()) {
            markdown.append("暂无学习画像。\n\n");
            return;
        }
        for (AiLearningProfileEntity profile : profiles) {
            markdown.append("### #").append(profile.id).append(" ").append(safeInline(firstNonBlank(profile.label, profile.profileKey, "未命名画像"))).append("\n\n");
            appendBullet(markdown, "分类", profile.category);
            appendBullet(markdown, "Key", profile.profileKey);
            appendBullet(markdown, "状态", statusLabel(profile.state));
            appendBullet(markdown, "置信度", formatDecimal(profile.confidence));
            appendBullet(markdown, "证据数", profile.evidenceCount == null ? "0" : String.valueOf(profile.evidenceCount));
            appendBullet(markdown, "最近证据", formatTime(profile.lastEvidenceAt));
            appendBullet(markdown, "召回状态", recallState(profile.state));
            List<AiLearningProfileEvidenceEntity> items = evidence.stream()
                    .filter(item -> profile.id != null && profile.id.equals(item.profileId))
                    .limit(5)
                    .toList();
            if (items.isEmpty()) {
                markdown.append("- 证据摘要：暂无可导出的安全证据。\n\n");
            } else {
                markdown.append("- 证据摘要：\n");
                for (AiLearningProfileEvidenceEntity item : items) {
                    markdown.append("  - #").append(item.id)
                            .append(" [").append(safeInline(item.evidenceType)).append("] ")
                            .append(safeInline(item.summary)).append('\n');
                }
                markdown.append('\n');
            }
        }
    }

    private void appendSubmissionAnalyses(StringBuilder markdown, List<AiSubmissionAnalysisEntity> analyses) {
        markdown.append("## 提交分析索引\n\n");
        if (analyses.isEmpty()) {
            markdown.append("暂无提交分析。\n\n");
            return;
        }
        for (AiSubmissionAnalysisEntity analysis : analyses) {
            markdown.append("- 分析 #").append(analysis.id)
                    .append(" / 提交 #").append(analysis.submissionId)
                    .append(" / 题目 #").append(analysis.problemId)
                    .append('\n');
            appendBullet(markdown, "状态", analysis.status, 2);
            appendBullet(markdown, "语言", analysis.language, 2);
            appendBullet(markdown, "Code Hash", analysis.codeHash, 2);
            appendBullet(markdown, "标签", analysis.rootCauseTags, 2);
            appendBullet(markdown, "创建时间", formatTime(analysis.createdAt), 2);
            appendBlock(markdown, "摘要", analysis.summary, 2);
        }
        markdown.append('\n');
    }

    private void appendCandidates(StringBuilder markdown, List<AiMemoryCandidateEntity> candidates) {
        markdown.append("## 待确认候选\n\n");
        if (candidates.isEmpty()) {
            markdown.append("暂无待确认候选。\n\n");
            return;
        }
        for (AiMemoryCandidateEntity candidate : candidates) {
            markdown.append("- 候选 #").append(candidate.id)
                    .append(" / ").append(safeInline(firstNonBlank(candidate.memoryKey, candidate.category, "未命名候选")))
                    .append('\n');
            appendBullet(markdown, "状态", candidate.status, 2);
            appendBullet(markdown, "分类", candidate.category, 2);
            appendBullet(markdown, "写入分", formatDecimal(candidate.writeScore), 2);
            appendBullet(markdown, "质量标记", candidate.qualityFlags, 2);
            appendBullet(markdown, "歧义标记", candidate.ambiguityFlags, 2);
            appendBlock(markdown, "安全摘要", candidate.canonicalText, 2);
        }
        markdown.append('\n');
    }

    private void appendMemoryEvidence(StringBuilder markdown, List<AiMemoryEvidenceEntity> evidence) {
        markdown.append("## 长期记忆证据索引\n\n");
        if (evidence.isEmpty()) {
            markdown.append("暂无长期记忆证据。\n\n");
            return;
        }
        for (AiMemoryEvidenceEntity item : evidence) {
            markdown.append("- 证据 #").append(item.id)
                    .append(" / Claim #").append(item.claimId)
                    .append('\n');
            appendBullet(markdown, "类型", item.evidenceType, 2);
            appendBullet(markdown, "置信度", formatDecimal(item.confidence), 2);
            appendBullet(markdown, "原因", item.reason, 2);
            appendBullet(markdown, "创建时间", formatTime(item.createdAt), 2);
            appendBlock(markdown, "摘要", item.evidenceText, 2);
        }
        markdown.append('\n');
    }

    private void appendBullet(StringBuilder markdown, String label, String value) {
        appendBullet(markdown, label, value, 0);
    }

    private void appendBullet(StringBuilder markdown, String label, String value, int indent) {
        markdown.append(" ".repeat(Math.max(0, indent)))
                .append("- ").append(label).append("：")
                .append(safeInline(value)).append('\n');
    }

    private void appendBlock(StringBuilder markdown, String label, String value) {
        appendBlock(markdown, label, value, 0);
    }

    private void appendBlock(StringBuilder markdown, String label, String value, int indent) {
        String safe = safeBlock(value);
        markdown.append(" ".repeat(Math.max(0, indent)))
                .append("- ").append(label).append("：")
                .append(safe.isBlank() ? "（已脱敏或暂无安全摘要）" : safe)
                .append('\n');
    }

    private String safeInline(String value) {
        return safeBlock(value).replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private String safeBlock(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return sanitizer.sanitizeText(value)
                .replace("`", "'")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String formatDecimal(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? "" : DISPLAY_TIME.format(value);
    }

    private String statusLabel(String status) {
        String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized;
    }

    private String recallState(String status) {
        String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (AiMemoryService.STATUS_ACTIVE.equals(normalized) || "CANDIDATE".equals(normalized)) {
            return "可参与后续召回策略";
        }
        if (AiMemoryService.STATUS_DISABLED.equals(normalized)
                || AiMemoryService.STATUS_RESOLVED.equals(normalized)
                || AiMemoryService.STATUS_SUPERSEDED.equals(normalized)) {
            return "不参与后续召回";
        }
        return "按当前服务策略处理";
    }

    public record ArchiveCounts(
            int memoryCount,
            int memoryHistoryCount,
            int learningProfileCount,
            int profileEvidenceCount,
            int submissionAnalysisCount,
            int pendingCandidateCount,
            int memoryEvidenceCount
    ) {
    }

    public record MarkdownArchive(String fileName, String markdown, ArchiveCounts counts) {
    }
}
