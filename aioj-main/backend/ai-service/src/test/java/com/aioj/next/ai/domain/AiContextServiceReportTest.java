package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.context.AiContextBuildReport;
import com.aioj.next.ai.domain.context.AiContextReportBuilder;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationProblemMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiContextServiceReportTest {
    private static final Long USER_ID = 7L;

    @Mock
    private AiConversationMapper conversationMapper;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AiConversationProblemMapper problemMapper;
    @Mock
    private AiMemoryService memoryService;
    @Mock
    private AiRetrievalService retrievalService;
    @Mock
    private AiLearningProfileService learningProfileService;
    @Mock
    private com.aioj.next.ai.domain.context.AiConversationContextV2Service contextV2Service;
    @Mock
    private AiProblemContextResolver problemContextResolver;
    @Mock
    private AiSubmissionContextResolver submissionContextResolver;

    @Test
    void buildReportContainsResolvedSubmissionContestPolicyAndKeepsPromptPackCompatible() {
        AiContextService service = new AiContextService(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                new AiContextReportBuilder(),
                new ObjectMapper()
        );
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId("c-report");
        conversation.setUserId(USER_ID);
        conversation.setSummary("上一轮 AI 给过 C++ 代码，需要反思。");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        AiChatRequest request = new AiChatRequest(
                "c-report",
                99L,
                "我提交了，答案错误",
                "assist",
                null,
                null,
                null,
                "client-1",
                null,
                new AiChatRequest.ContestContext(1L, 2L, 3L),
                new AiChatRequest.SubmissionContext(123L, "EXPLAIN_ERROR", true, "status: WA")
        );
        AiProblemContextResponse problem = new AiProblemContextResponse(
                99L,
                1L,
                2L,
                3L,
                "星港建设",
                "MEDIUM",
                "完整题面不应进入 report preview",
                "选择 m 个星港，让最小距离最大。",
                List.of("binary_search_on_answer", "greedy"),
                List.of("n <= 2e5"),
                List.of(),
                1000,
                262144,
                "problem-service",
                Instant.now()
        );
        AiSubmissionContextResponse submission = new AiSubmissionContextResponse(
                123L,
                USER_ID,
                99L,
                1L,
                2L,
                3L,
                "CONTEST",
                true,
                "cpp",
                "WRONG_ANSWER",
                "Wrong answer on case 2",
                "stdout secret",
                "stderr secret",
                0,
                12,
                2048,
                0.0,
                100.0,
                false,
                "int main(){return 0;}",
                "sha256-submission",
                List.of(new AiSubmissionCaseContext(1, "case 2", "WRONG_ANSWER", 0.0, 1.0, 12, 2048, "case 2 failed")),
                problem,
                Instant.now(),
                Instant.now(),
                "比赛进行中隐藏源码。"
        );
        ContestAiPolicyResponse policy = new ContestAiPolicyResponse(
                true,
                1L,
                2L,
                3L,
                99L,
                "周赛",
                "正式赛",
                "星港建设"
        );
        when(submissionContextResolver.resolve(USER_ID, request)).thenReturn(submission);
        when(submissionContextResolver.contextBlock(submission)).thenReturn("""
                [Selected Submission Context]
                submissionId: 123
                status: WRONG_ANSWER
                codeAllowedToModel: false
                [Submission Code Redacted]
                """);
        when(submissionContextResolver.safeSummary(submission)).thenReturn(Map.of(
                "submissionId", "123",
                "problemId", "99",
                "status", "WRONG_ANSWER",
                "codeAllowedToModel", false,
                "codeHash", "sha256-submission",
                "caseResults", List.of(Map.of("caseIndex", 1, "message", "case 2 failed")),
                "source", "resolved.submissionContext"
        ));
        when(problemContextResolver.safeSummary(problem)).thenReturn(Map.of(
                "problemId", "99",
                "title", "星港建设",
                "tags", List.of("binary_search_on_answer", "greedy"),
                "constraints", List.of("n <= 2e5")
        ));
        when(contextV2Service.contextPack(eq(USER_ID), eq("c-report"), eq(request), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String memories = invocation.getArgument(3);
                    String history = invocation.getArgument(4);
                    if ((memories == null || memories.isBlank()) && (history == null || history.isBlank())) {
                        return "[Current Conversation State]\n- latestSubmissionId: 123";
                    }
                    return """
                            [Current Conversation State]
                            - latestSubmissionId: 123

                            [Teaching Strategy]
                            - 若上一轮 AI 给过代码，需要反思建议代码。
                            """;
                });
        when(problemMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(memoryService.recallForContext(eq(USER_ID), anyString())).thenReturn("[ACTIVE]\n学生容易漏边界。");
        when(retrievalService.searchDetailed(eq(USER_ID), anyString(), anyList(), eq(5), any(AiRetrievalService.AiRetrievalSearchContext.class)))
                .thenReturn(List.of(new AiRetrievalService.AiRetrievalHit(
                        "submission_analysis",
                        "123",
                        "上一轮 assistant 给过可运行代码，需要结合本次 WRONG_ANSWER 反思。",
                        4.2,
                        List.of("same_submission", "same_problem"),
                        99L,
                        123L,
                        1L,
                        2L,
                        3L,
                        "binary_search_on_answer",
                        "wrong_answer_binary_search",
                        AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE,
                        Map.of("source", "submission_analysis")
                )));
        when(memoryService.filterRecallableRetrievalHits(eq(USER_ID), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        AiChatContext context = service.build(USER_ID, conversation, request, policy);
        AiContextBuildReport report = context.contextBuildReport();

        assertThat(context.conversationContextPack()).contains("[Selected Submission Context]", "[Teaching Strategy]");
        assertThat(report.sections()).extracting("type")
                .contains("user_message", "problem_context", "submission_context", "contest_policy", "conversation_state", "teaching_strategy", "long_term_memory", "retrieved_history");
        assertThat(report.sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("contest.ai_policy");
            assertThat(section.required()).isTrue();
            assertThat(section.sensitivity()).isEqualTo("policy");
        });
        assertThat(report.sections()).filteredOn(section -> section.id().equals("resolved.submission"))
                .singleElement()
                .satisfies(section -> assertThat(section.contentPreview())
                        .contains("WRONG_ANSWER", "sha256-submission")
                        .doesNotContain("int main", "stdout secret", "stderr secret", "codeText", "stdoutExcerpt", "stderrExcerpt"));
        assertThat(report.sections()).filteredOn(section -> section.id().equals("retrieval.history"))
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.metadata()).containsKey("hits");
                    assertThat(section.metadata().get("hits").toString())
                            .contains("submission_analysis", "same_submission", "4.2")
                            .doesNotContain("int main", "stdout secret", "stderr secret");
                });
    }

    @Test
    void buildAddsRelatedSameProblemSubmissionHistory() {
        AiContextService service = new AiContextService(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                new AiContextReportBuilder(),
                new ObjectMapper()
        );
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId("c-current-submission");
        conversation.setUserId(USER_ID);
        conversation.setProblemId(99L);
        conversation.setSource("submission_analysis");
        conversation.setSourceRefType("SUBMISSION");
        conversation.setSourceRefId("123");
        conversation.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        conversation.setUpdatedAt(LocalDateTime.now());
        AiChatRequest request = new AiChatRequest(
                "c-current-submission",
                99L,
                "继续分析这次提交为什么超时",
                "submission_analysis",
                null,
                null,
                null,
                "client-related",
                null,
                null,
                new AiChatRequest.SubmissionContext(123L, "ANALYZE_SUBMISSION", true, "status: TLE")
        );
        AiConversationEntity oldSubmission = new AiConversationEntity();
        oldSubmission.setId("c-old-submission");
        oldSubmission.setUserId(USER_ID);
        oldSubmission.setProblemId(99L);
        oldSubmission.setSource("submission_analysis");
        oldSubmission.setSourceRefType("SUBMISSION");
        oldSubmission.setSourceRefId("122");
        oldSubmission.setSummary("""
                旧提交分析：cpp 代码在 TLE 上暴露了二重循环风险。
                ```cpp
                int main(){ return 0; }
                ```
                可复用结论：先检查排序后 DP 转移。
                """);
        oldSubmission.setUpdatedAt(LocalDateTime.now().minusMinutes(2));
        AiConversationEntity duplicateSameSubmission = new AiConversationEntity();
        duplicateSameSubmission.setId("c-duplicate");
        duplicateSameSubmission.setUserId(USER_ID);
        duplicateSameSubmission.setProblemId(99L);
        duplicateSameSubmission.setSource("submission_analysis");
        duplicateSameSubmission.setSourceRefType("SUBMISSION");
        duplicateSameSubmission.setSourceRefId("123");
        duplicateSameSubmission.setSummary("同一提交的旧重复会话不应进入相关历史。");
        duplicateSameSubmission.setUpdatedAt(LocalDateTime.now().minusMinutes(1));
        AiConversationEntity tutorConversation = new AiConversationEntity();
        tutorConversation.setId("c-tutor");
        tutorConversation.setUserId(USER_ID);
        tutorConversation.setProblemId(99L);
        tutorConversation.setSource("ai_tutor");
        tutorConversation.setSummary("普通辅导历史：本题区间按右端点排序后再考虑转移。");
        tutorConversation.setUpdatedAt(LocalDateTime.now().minusMinutes(3));

        when(submissionContextResolver.resolve(USER_ID, request)).thenReturn(null);
        when(problemContextResolver.resolve(USER_ID, request)).thenReturn(null);
        when(problemContextResolver.contextBlock(null)).thenReturn("");
        when(problemContextResolver.safeSummary((AiProblemContextResponse) null)).thenReturn(Map.of("problemId", "99", "tags", List.of("dp", "sort")));
        when(problemMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(memoryService.recallForContext(eq(USER_ID), anyString())).thenReturn("");
        when(retrievalService.searchDetailed(eq(USER_ID), anyString(), anyList(), eq(5), any(AiRetrievalService.AiRetrievalSearchContext.class)))
                .thenReturn(List.of());
        when(conversationMapper.selectList(any())).thenReturn(List.of(oldSubmission, duplicateSameSubmission, tutorConversation));
        when(contextV2Service.contextPack(eq(USER_ID), eq("c-current-submission"), eq(request), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String history = invocation.getArgument(4);
                    if (history == null || history.isBlank()) {
                        return "[Current Conversation State]\n- latestSubmissionId: 123";
                    }
                    return "[Current Conversation State]\n- latestSubmissionId: 123\n\n[Retrieved Past Context]\n" + history;
                });

        AiChatContext context = service.build(USER_ID, conversation, request, ContestAiPolicyResponse.inactive());

        assertThat(context.retrievedHistory())
                .contains("[Related Same-Problem Submission Memories]", "relatedSubmissionMemories", "旧提交分析", "普通辅导历史")
                .contains("[code omitted]")
                .doesNotContain("同一提交的旧重复会话", "int main");
        assertThat(context.conversationContextPack()).contains("Current submission facts override them");
    }

    @Test
    void buildSynthesizesContestPolicyFromActiveSelectedSubmissionWithoutRequestProblemId() {
        AiContextService service = new AiContextService(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                new AiContextReportBuilder(),
                new ObjectMapper()
        );
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId("c-submission-policy");
        conversation.setUserId(USER_ID);
        conversation.setSummary("");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        AiChatRequest request = new AiChatRequest(
                "c-submission-policy",
                null,
                "我提交了，答案错误",
                "assist",
                null,
                null,
                null,
                "client-2",
                null,
                null,
                new AiChatRequest.SubmissionContext(123L, "EXPLAIN_ERROR", true, "status: WA")
        );
        AiProblemContextResponse problem = new AiProblemContextResponse(
                99L,
                1L,
                2L,
                3L,
                "星港建设",
                "MEDIUM",
                "完整题面不应进入 report preview",
                "选择 m 个星港，让最小距离最大。",
                List.of("binary_search_on_answer"),
                List.of("n <= 2e5"),
                List.of(),
                1000,
                262144,
                "problem-service",
                Instant.now()
        );
        AiSubmissionContextResponse submission = new AiSubmissionContextResponse(
                123L,
                USER_ID,
                99L,
                1L,
                2L,
                3L,
                "CONTEST",
                true,
                "cpp",
                "WRONG_ANSWER",
                "Wrong answer on case 2",
                null,
                null,
                0,
                12,
                2048,
                0.0,
                100.0,
                false,
                "int main(){return 0;}",
                "sha256-submission",
                List.of(),
                problem,
                Instant.now(),
                Instant.now(),
                "比赛进行中隐藏源码。"
        );
        when(submissionContextResolver.resolve(USER_ID, request)).thenReturn(submission);
        when(submissionContextResolver.contextBlock(submission)).thenReturn("""
                [Selected Submission Context]
                submissionId: 123
                contestActive: true
                status: WRONG_ANSWER
                codeAllowedToModel: false
                [Submission Code Redacted]
                """);
        when(submissionContextResolver.safeSummary(submission)).thenReturn(Map.of(
                "submissionId", "123",
                "problemId", "99",
                "contestActive", true,
                "status", "WRONG_ANSWER",
                "codeAllowedToModel", false,
                "codeHash", "sha256-submission",
                "source", "resolved.submissionContext"
        ));
        when(problemContextResolver.safeSummary(problem)).thenReturn(Map.of(
                "problemId", "99",
                "title", "星港建设"
        ));
        when(contextV2Service.contextPack(eq(USER_ID), eq("c-submission-policy"), eq(request), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String memories = invocation.getArgument(3);
                    String history = invocation.getArgument(4);
                    if ((memories == null || memories.isBlank()) && (history == null || history.isBlank())) {
                        return "[Current Conversation State]\n- latestSubmissionId: 123";
                    }
                    return """
                            [Current Conversation State]
                            - latestSubmissionId: 123

                            [Teaching Strategy]
                            - 比赛中只给调试方向。
                            """;
                });
        when(problemMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(memoryService.recallForContext(eq(USER_ID), anyString())).thenReturn("");

        AiChatContext context = service.build(USER_ID, conversation, request, ContestAiPolicyResponse.inactive());

        assertThat(context.contestPolicy().activeContestProblem()).isTrue();
        assertThat(context.contestPolicy().allowFullCodeInResponse()).isFalse();
        assertThat(context.contestPolicy().allowDebugGuidance()).isTrue();
        assertThat(context.contestPolicy().problemId()).isEqualTo(99L);
        assertThat(context.contextBuildReport().sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo("contest_policy");
            assertThat(section.required()).isTrue();
            assertThat(section.contentPreview())
                    .contains("activeContestProblem", "allowFullCodeInResponse", "false")
                    .doesNotContain("int main");
        });
    }

    @Test
    void buildFiltersNonRecallableProfileRetrievalHitsBeforeReport() {
        AiContextService service = new AiContextService(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                learningProfileService,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                new AiContextReportBuilder(),
                new ObjectMapper()
        );
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId("c-profile-filter");
        conversation.setUserId(USER_ID);
        conversation.setSummary("");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        AiChatRequest request = new AiChatRequest(
                "c-profile-filter",
                null,
                "回顾一下这题相关的学习画像",
                "assist",
                null,
                null,
                null,
                "client-profile-filter",
                null
        );
        AiRetrievalService.AiRetrievalHit resolvedProfileHit = new AiRetrievalService.AiRetrievalHit(
                "learning_profile",
                "10",
                "已解决的旧弱点不应继续召回。",
                3.0,
                List.of("semantic_match"),
                null,
                null,
                null,
                null,
                null,
                null,
                "wrong_answer_binary_search",
                AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE,
                Map.of("profileKey", "wrong_answer_binary_search")
        );
        AiRetrievalService.AiRetrievalHit ordinaryMessageHit = new AiRetrievalService.AiRetrievalHit(
                "message",
                "20",
                "普通安全历史仍可召回。",
                2.0,
                List.of("semantic_match"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE,
                Map.of()
        );
        when(submissionContextResolver.resolve(USER_ID, request)).thenReturn(null);
        when(problemContextResolver.resolve(USER_ID, request)).thenReturn(null);
        when(problemContextResolver.contextBlock(null)).thenReturn("");
        when(problemContextResolver.safeSummary(null)).thenReturn(Map.of());
        when(problemMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(memoryService.recallForContext(eq(USER_ID), anyString())).thenReturn("");
        when(contextV2Service.contextPack(eq(USER_ID), eq("c-profile-filter"), eq(request), anyString(), anyString()))
                .thenReturn("""
                        [Current Conversation State]
                        - latestSubmissionId: none

                        [Teaching Strategy]
                        - 保持提示式辅导。
                        """);
        when(retrievalService.searchDetailed(eq(USER_ID), anyString(), anyList(), eq(5), any(AiRetrievalService.AiRetrievalSearchContext.class)))
                .thenReturn(List.of(resolvedProfileHit, ordinaryMessageHit));
        when(memoryService.filterRecallableRetrievalHits(eq(USER_ID), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(learningProfileService.filterRecallableRetrievalHits(eq(USER_ID), anyList()))
                .thenReturn(List.of(ordinaryMessageHit));

        AiChatContext context = service.build(USER_ID, conversation, request, ContestAiPolicyResponse.inactive());

        assertThat(context.retrievedHistory()).contains("普通安全历史仍可召回").doesNotContain("旧弱点");
        assertThat(context.contextBuildReport().sections()).filteredOn(section -> section.id().equals("retrieval.history"))
                .singleElement()
                .satisfies(section -> assertThat(section.metadata().get("hits").toString())
                        .contains("message")
                        .doesNotContain("learning_profile", "旧弱点"));
        verify(learningProfileService).filterRecallableRetrievalHits(eq(USER_ID), anyList());
    }
}
