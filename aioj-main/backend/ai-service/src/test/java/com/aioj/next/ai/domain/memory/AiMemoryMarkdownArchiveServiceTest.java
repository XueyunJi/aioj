package com.aioj.next.ai.domain.memory;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryMarkdownArchiveServiceTest {
    @Mock
    private AiUserMemoryMapper memoryMapper;
    @Mock
    private AiLearningProfileMapper profileMapper;
    @Mock
    private AiLearningProfileEvidenceMapper profileEvidenceMapper;
    @Mock
    private AiSubmissionAnalysisMapper submissionAnalysisMapper;
    @Mock
    private AiMemoryCandidateMapper candidateMapper;
    @Mock
    private AiMemoryEvidenceMapper memoryEvidenceMapper;
    @Mock
    private OperationAuditWriter auditWriter;

    private AiMemoryMarkdownArchiveService service;

    @BeforeEach
    void setUp() {
        service = new AiMemoryMarkdownArchiveService(
                memoryMapper,
                profileMapper,
                profileEvidenceMapper,
                submissionAnalysisMapper,
                candidateMapper,
                memoryEvidenceMapper,
                new AiMemoryEventPayloadSanitizer(),
                auditWriter
        );
    }

    @Test
    void exportIncludesArchiveSectionsAndWritesSafeAudit() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(), supersededMemory()));
        when(profileMapper.selectList(any())).thenReturn(List.of(profile("RESOLVED", null, null)));
        when(profileEvidenceMapper.selectList(any())).thenReturn(List.of(profileEvidence()));
        when(submissionAnalysisMapper.selectList(any())).thenReturn(List.of(submissionAnalysis()));
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate()));
        when(memoryEvidenceMapper.selectList(any())).thenReturn(List.of(memoryEvidence()));

        AiMemoryMarkdownArchiveService.MarkdownArchive archive = service.export(7L);

        assertThat(archive.fileName()).startsWith("aioj-learning-archive-").endsWith(".md");
        assertThat(archive.markdown())
                .contains("# AI-OJ AI 学习档案")
                .contains("## 当前可召回长期记忆")
                .contains("#901")
                .contains("## 合并与停用历史")
                .contains("#902")
                .contains("## 学习画像")
                .contains("#301")
                .contains("## 提交分析索引")
                .contains("提交 #333")
                .contains("## 待确认候选")
                .contains("候选 #444")
                .contains("## 长期记忆证据索引")
                .contains("不参与后续召回");
        assertNoUnsafeText(archive.markdown());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(
                eq("AI_MEMORY_MARKDOWN_EXPORT"),
                eq("AI_MEMORY_ARCHIVE"),
                eq(null),
                eq("SUCCESS"),
                summaryCaptor.capture(),
                eq(7L),
                eq(null),
                eq(null),
                eq(7L)
        );
        assertThat(summaryCaptor.getValue())
                .containsEntry("memoryCount", 1)
                .containsEntry("memoryHistoryCount", 1)
                .containsEntry("learningProfileCount", 1)
                .containsEntry("pendingCandidateCount", 1);
        assertThat(summaryCaptor.getValue().toString()).doesNotContain("int main", "stdout:", "sk-test-secret");
    }

    @Test
    void exportOmitsDeletedProfilesAndStillReturnsReadableEmptySections() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(profileMapper.selectList(any())).thenReturn(List.of());
        when(submissionAnalysisMapper.selectList(any())).thenReturn(List.of());
        when(candidateMapper.selectList(any())).thenReturn(List.of());
        when(memoryEvidenceMapper.selectList(any())).thenReturn(List.of());

        AiMemoryMarkdownArchiveService.MarkdownArchive archive = service.export(7L);

        assertThat(archive.markdown())
                .contains("暂无当前可召回长期记忆")
                .contains("暂无合并或停用历史")
                .contains("暂无学习画像")
                .contains("暂无提交分析")
                .contains("暂无待确认候选");
        assertNoUnsafeText(archive.markdown());
        verify(auditWriter).record(any(), any(), any(), any(), any(), eq(7L), any(), any(), eq(7L));
    }

    private AiUserMemoryEntity memory() {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(901L);
        memory.setUserId(7L);
        memory.setCategory("preference");
        memory.setTitle("讲解偏好");
        memory.setMemoryType("guidance_preference");
        memory.setContent("""
                我喜欢先给提示。
                ```cpp
                int main() { return 0; }
                ```
                stdout: hidden output
                token=sk-test-secret
                """);
        memory.setConfidence(BigDecimal.valueOf(0.91));
        memory.setSource("USER_CONFIRMED");
        memory.setStatus("ACTIVE");
        memory.setUpdatedAt(LocalDateTime.of(2026, 6, 25, 10, 0));
        return memory;
    }

    private AiUserMemoryEntity supersededMemory() {
        AiUserMemoryEntity memory = memory();
        memory.setId(902L);
        memory.setMemoryType("answer_style_preference");
        memory.setContent("用户偏好直接获得完整代码，而非分步讲解或提示。");
        memory.setStatus("SUPERSEDED");
        memory.setSource("AI_MEMORY_MERGED");
        memory.setUpdatedAt(LocalDateTime.of(2026, 6, 25, 11, 0));
        return memory;
    }

    private AiLearningProfileEntity profile(String state, LocalDateTime deletedAt, LocalDateTime disabledAt) {
        AiLearningProfileEntity profile = new AiLearningProfileEntity();
        profile.id = 301L;
        profile.userId = 7L;
        profile.category = "weakness";
        profile.profileKey = "binary_search_boundary";
        profile.label = "二分边界";
        profile.state = state;
        profile.confidence = BigDecimal.valueOf(0.62);
        profile.evidenceCount = 1;
        profile.deletedAt = deletedAt;
        profile.disabledAt = disabledAt;
        profile.lastEvidenceAt = LocalDateTime.of(2026, 6, 24, 9, 0);
        profile.updatedAt = LocalDateTime.of(2026, 6, 24, 9, 0);
        return profile;
    }

    private AiLearningProfileEvidenceEntity profileEvidence() {
        AiLearningProfileEvidenceEntity evidence = new AiLearningProfileEvidenceEntity();
        evidence.id = 302L;
        evidence.userId = 7L;
        evidence.profileId = 301L;
        evidence.evidenceType = "SUBMISSION_ANALYSIS";
        evidence.summary = "prompt: internal prompt\ncheck 函数边界不稳定";
        evidence.confidence = BigDecimal.valueOf(0.62);
        evidence.createdAt = LocalDateTime.of(2026, 6, 24, 9, 5);
        return evidence;
    }

    private AiSubmissionAnalysisEntity submissionAnalysis() {
        AiSubmissionAnalysisEntity analysis = new AiSubmissionAnalysisEntity();
        analysis.id = 401L;
        analysis.userId = 7L;
        analysis.submissionId = 333L;
        analysis.problemId = 222L;
        analysis.status = "WRONG_ANSWER";
        analysis.language = "cpp";
        analysis.codeHash = "abc123";
        analysis.rootCauseTags = "[\"boundary\"]";
        analysis.summary = "sourceCode: int main() { return 0; }\n需要检查边界。";
        analysis.createdAt = LocalDateTime.of(2026, 6, 24, 9, 10);
        return analysis;
    }

    private AiMemoryCandidateEntity candidate() {
        AiMemoryCandidateEntity candidate = new AiMemoryCandidateEntity();
        candidate.id = 444L;
        candidate.userId = 7L;
        candidate.category = "weakness";
        candidate.memoryKey = "candidate_weakness";
        candidate.canonicalText = "Bearer abcdefghijklmnop\n我可能需要确认二分答案。";
        candidate.writeScore = BigDecimal.valueOf(0.55);
        candidate.qualityFlags = "[\"high_impact_weakness\"]";
        candidate.ambiguityFlags = "[\"low_confidence\"]";
        candidate.status = "NEEDS_CONFIRMATION";
        candidate.updatedAt = LocalDateTime.of(2026, 6, 24, 9, 20);
        return candidate;
    }

    private AiMemoryEvidenceEntity memoryEvidence() {
        AiMemoryEvidenceEntity evidence = new AiMemoryEvidenceEntity();
        evidence.id = 501L;
        evidence.userId = 7L;
        evidence.claimId = 601L;
        evidence.evidenceType = "SUPPORT";
        evidence.evidenceText = "#include <bits/stdc++.h>\n用户确认偏好。";
        evidence.confidence = BigDecimal.valueOf(0.8);
        evidence.reason = "manual_accept";
        evidence.createdAt = LocalDateTime.of(2026, 6, 24, 9, 30);
        return evidence;
    }

    private void assertNoUnsafeText(String markdown) {
        assertThat(markdown)
                .doesNotContain("```")
                .doesNotContain("int main")
                .doesNotContain("#include")
                .doesNotContain("stdout:")
                .doesNotContain("hidden output")
                .doesNotContain("sourceCode:")
                .doesNotContain("prompt:")
                .doesNotContain("Bearer abcdefghijklmnop")
                .doesNotContain("sk-test-secret");
    }
}
