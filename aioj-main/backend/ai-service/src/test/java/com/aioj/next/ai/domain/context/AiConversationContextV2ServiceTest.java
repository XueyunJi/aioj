package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiChatContext;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiRetrievalService;
import com.aioj.next.ai.persistence.entity.AiClarificationAnswerEntity;
import com.aioj.next.ai.persistence.entity.AiClarificationRequestEntity;
import com.aioj.next.ai.persistence.entity.AiCodeSnapshotEntity;
import com.aioj.next.ai.persistence.entity.AiConversationSummaryEntity;
import com.aioj.next.ai.persistence.entity.AiConversationTaskStateEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiClarificationAnswerMapper;
import com.aioj.next.ai.persistence.mapper.AiClarificationRequestMapper;
import com.aioj.next.ai.persistence.mapper.AiCodeSnapshotMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationSummaryMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationTaskStateMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiConversationContextV2ServiceTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-service";

    @Mock
    private AiConversationTaskStateMapper taskStateMapper;
    @Mock
    private AiConversationSummaryMapper summaryMapper;
    @Mock
    private AiCodeSnapshotMapper codeSnapshotMapper;
    @Mock
    private AiClarificationRequestMapper clarificationRequestMapper;
    @Mock
    private AiClarificationAnswerMapper clarificationAnswerMapper;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AiConversationMapper conversationMapper;
    @Mock
    private AiRetrievalService retrievalService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<AiConversationTaskStateEntity> stateRef = new AtomicReference<>();
    private ConversationStateMerger merger;
    private AiConversationContextV2Service service;

    @BeforeEach
    void setUp() {
        merger = new ConversationStateMerger(objectMapper);
        service = new AiConversationContextV2Service(
                taskStateMapper,
                summaryMapper,
                codeSnapshotMapper,
                clarificationRequestMapper,
                clarificationAnswerMapper,
                messageMapper,
                conversationMapper,
                retrievalService,
                objectMapper,
                merger,
                new ConversationContextPackBuilder(merger, objectMapper),
                new ConversationCompressionService(merger, objectMapper),
                new ClarificationDeduplicator()
        );
        lenient().when(taskStateMapper.selectOne(any())).thenAnswer(ignored -> stateRef.get());
        lenient().doAnswer(invocation -> {
            AiConversationTaskStateEntity entity = invocation.getArgument(0);
            entity.id = 100L;
            stateRef.set(entity);
            return 1;
        }).when(taskStateMapper).insert(any(AiConversationTaskStateEntity.class));
        lenient().doAnswer(invocation -> {
            AiConversationTaskStateEntity entity = invocation.getArgument(0);
            stateRef.set(entity);
            return 1;
        }).when(taskStateMapper).updateById(any(AiConversationTaskStateEntity.class));
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of());
        lenient().when(summaryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(clarificationRequestMapper.selectList(any())).thenReturn(List.of());
        lenient().when(clarificationAnswerMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void beforeTurnPastedProblemWritesStateAndContextPack() {
        AiChatRequest request = request("星港建设：选 m 个星港，让最小距离最大。2 <= m <= n <= 2e5，xi <= 1e9。", null);

        service.beforeTurn(USER_ID, CONVERSATION_ID, request);
        Map<String, Object> state = merger.readState(stateRef.get().stateJson);
        String pack = service.contextPack(USER_ID, CONVERSATION_ID, request, "", "");

        assertThat(map(state.get("problem")).get("statementSummary")).asString().contains("最小距离最大");
        assertThat(list(map(state.get("problem")).get("constraints"))).contains("2 <= m <= n <= 2e5", "0 <= xi <= 1e9");
        assertThat(list(map(state.get("problem")).get("tags"))).contains("binary_search_on_answer", "greedy");
        assertThat(map(state.get("algorithmState")).get("candidateApproach")).asString().contains("二分答案");
        assertThat(pack).contains("最大化最小距离").contains("n <= 2e5").contains("排序 + 二分答案 + 贪心");
    }

    @Test
    void beforeTurnClarificationAnswerPersistsAnswerAndMergedState() {
        String pendingState = merger.mergeAfterCompletion(null, null, request("这题怎么入手？", null), new AiCompletion(
                "请回答。",
                "mock",
                "mock",
                1,
                1,
                clarification(CLARIFICATION_QUESTION)
        ));
        seedState(pendingState);
        AiClarificationRequestEntity pending = new AiClarificationRequestEntity();
        pending.id = 11L;
        pending.requestKey = "clarify_check";
        pending.question = CLARIFICATION_QUESTION;
        pending.status = "PENDING";
        when(clarificationRequestMapper.selectOne(any())).thenReturn(pending);

        service.beforeTurn(USER_ID, CONVERSATION_ID, new AiChatRequest(
                CONVERSATION_ID,
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer("clarify_check", CLARIFICATION_QUESTION, "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        ));

        ArgumentCaptor<AiClarificationAnswerEntity> captor = ArgumentCaptor.forClass(AiClarificationAnswerEntity.class);
        verify(clarificationAnswerMapper).insert(captor.capture());
        AiClarificationAnswerEntity saved = captor.getValue();
        Map<String, Object> state = merger.readState(stateRef.get().stateJson);
        assertThat(saved.interpretedDeltaJson).contains("userKnownPoint");
        assertThat(saved.mergedToState).isTrue();
        assertThat(list(map(state.get("learningFlow")).get("answeredClarificationIds"))).contains("clarify_check");
        assertThat(list(map(state.get("learningFlow")).get("pendingClarificationIds"))).doesNotContain("clarify_check");
        assertThat(service.contextPack(USER_ID, CONVERSATION_ID, new AiChatRequest(
                CONVERSATION_ID,
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer("clarify_check", CLARIFICATION_QUESTION, "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        ), "", "")).contains("Clarification Answer Just Submitted");
    }

    @Test
    void afterTurnStoresSpecificFollowUpInsteadOfRepeatedClarification() {
        String state = merger.mergeBeforePrompt(null, request("星港建设：选 m 个星港，让最小距离最大，n <= 2e5，xi <= 1e9。", null)).stateJson();
        state = merger.mergeBeforePrompt(state, new AiChatRequest(
                CONVERSATION_ID,
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer("clarify_check", CLARIFICATION_QUESTION, "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        )).stateJson();
        seedState(state);
        when(clarificationRequestMapper.selectOne(any())).thenReturn(null);
        AiCompletion repeated = new AiCompletion(
                "继续。",
                "mock",
                "mock",
                1,
                1,
                clarification("如何检查这个距离是否合理？")
        );

        AiCompletion prepared = service.prepareCompletionForTurn(USER_ID, CONVERSATION_ID, repeated);
        service.afterTurn(USER_ID, CONVERSATION_ID, 1L, 2L, request("继续", null), repeated, new AiChatContext("", "", "", "", ""));

        assertThat(prepared.clarification().prompt()).contains("最靠左").contains("最远");
        ArgumentCaptor<AiClarificationRequestEntity> captor = ArgumentCaptor.forClass(AiClarificationRequestEntity.class);
        verify(clarificationRequestMapper).insert(captor.capture());
        assertThat(captor.getValue().question).contains("最靠左").contains("最远");
        assertThat(captor.getValue().requestKey).startsWith("clarify_follow_up_");
    }

    @Test
    void contextDebugReturnsStateSummariesClarificationsContextPackAndWarnings() {
        seedState(merger.mergeBeforePrompt(null, request("星港建设：选 m 个星港，让最小距离最大，n <= 2e5，xi <= 1e9。", null)).stateJson());
        AiConversationSummaryEntity summary = new AiConversationSummaryEntity();
        summary.id = 21L;
        summary.summaryType = "compact";
        summary.narrativeSummary = "当前题目：星港建设。";
        summary.structuredSummary = """
                {"problem":{"title":"星港建设"},"keptSegments":[{"reason":"problem_statement","salience":0.9}],"salienceScore":0.9,"tokenEstimate":32}
                """;
        summary.messageStartId = 1L;
        summary.messageEndId = 2L;
        summary.tokenEstimate = 32;
        summary.createdAt = LocalDateTime.now();
        when(summaryMapper.selectList(any())).thenReturn(List.of(summary));
        when(messageMapper.selectList(any())).thenReturn(List.of(message(1L, "user", "星港建设题。")));
        AiClarificationRequestEntity pending = new AiClarificationRequestEntity();
        pending.id = 31L;
        pending.requestKey = "clarify_check";
        pending.question = "如何检查 d？";
        pending.priority = "helpful";
        pending.status = "PENDING";
        pending.inputSchema = "{}";
        pending.createdAt = LocalDateTime.now();
        when(clarificationRequestMapper.selectList(any())).thenReturn(List.of(pending));
        AiClarificationAnswerEntity answer = new AiClarificationAnswerEntity();
        answer.id = 41L;
        answer.requestKey = "clarify_old";
        answer.question = "旧问题";
        answer.answerText = "旧答案";
        answer.interpretedDeltaJson = "{\"ok\":true}";
        answer.mergedToState = true;
        answer.createdAt = LocalDateTime.now();
        when(clarificationAnswerMapper.selectList(any())).thenReturn(List.of(answer));

        AiConversationContextDebugResponse debug = service.contextDebug(USER_ID, CONVERSATION_ID, "[User Rules]\n完整代码默认 C++。", "");

        assertThat(debug.state()).containsKey("problem");
        assertThat(debug.recentMessages()).hasSize(1);
        assertThat(debug.pendingClarifications()).hasSize(1);
        assertThat(debug.answeredClarifications()).hasSize(1);
        assertThat(debug.summarySegments()).hasSize(1);
        assertThat(debug.summarySegments().get(0).salienceScore()).isGreaterThan(0);
        assertThat(debug.contextPackPreview()).contains("星港").contains("完整代码默认 C++");
        assertThat(debug.contextPackPreview()).doesNotContain("DISABLED");
        assertThat(debug.sections()).extracting(AiConversationContextDebugResponse.ContextSection::type)
                .contains("problem_context", "conversation_state", "recent_messages", "compressed_summaries", "long_term_memory");
        assertThat(debug.sourceSummary()).containsKeys("ai-service.state", "ai-service.messages", "ai-service.memory");
        assertThat(debug.contextBuildReport().totalEstimatedTokens()).isGreaterThan(0);
        assertThat(debug.contextBuildReport().requiredSectionCount()).isGreaterThan(0);
        assertThat(debug.warnings()).isEmpty();
    }

    @Test
    void contextDebugPreviewMasksSecretsOmitsCodeBodiesAndFiltersDisabledMemories() {
        seedState("""
                {
                  "problem": {"title": "星港建设"},
                  "codeState": {
                    "latestCodeHash": "hash-abc",
                    "latestCodeMessageId": "88",
                    "latestCode": "#include <bits/stdc++.h>\\nint main(){return 0;}\\nsk-live-secret-123"
                  }
                }
                """);
        when(messageMapper.selectList(any())).thenReturn(List.of(message(88L, "user", """
                ```cpp
                #include <bits/stdc++.h>
                using namespace std;
                int main(){ return 0; }
                ```
                token=plain-secret-123
                """)));

        AiConversationContextDebugResponse debug = service.contextDebug(
                USER_ID,
                CONVERSATION_ID,
                """
                        [ACTIVE]
                        完整代码默认 C++。
                        [DISABLED]
                        禁用偏好：直接给 Python 完整代码。
                        [ACTIVE]
                        先给提示。
                        """,
                "retrieved token=abc123 sk-live-secret-123"
        );
        Map<String, Object> codeState = map(debug.state().get("codeState"));

        assertThat(codeState.get("latestCodeHash")).isEqualTo("hash-abc");
        assertThat(codeState.get("latestCodeMessageId")).isEqualTo("88");
        assertThat(codeState.get("latestCode")).isEqualTo("[code omitted in context-debug preview]");
        assertThat(debug.recentMessages().get(0).contentPreview()).contains("code block omitted");
        assertThat(debug.contextPackPreview()).contains("完整代码默认 C++").contains("先给提示");
        assertThat(debug.contextPackPreview()).contains("token=***").contains("sk-***");
        assertThat(debug.contextPackPreview()).doesNotContain("禁用偏好");
        assertThat(debug.contextPackPreview()).doesNotContain("Python 完整代码");
        assertThat(debug.contextPackPreview()).doesNotContain("#include");
        assertThat(debug.contextPackPreview()).doesNotContain("int main");
        assertThat(debug.contextPackPreview()).doesNotContain("plain-secret-123");
        assertThat(debug.contextPackPreview()).doesNotContain("sk-live-secret-123");
        assertThat(debug.contextBuildReport().sections()).allSatisfy(section ->
                assertThat(section.contentPreview())
                        .doesNotContain("#include", "int main", "plain-secret-123", "sk-live-secret-123")
        );
    }

    @Test
    void compactBeforeProviderDoesNotWriteInvalidSummary() {
        seedState("{}");
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(1L, "user", "继续"),
                message(2L, "assistant", "好的")
        ));

        AiConversationContextV2Service.PreProviderCompressionResult result =
                service.compactBeforeProvider(USER_ID, CONVERSATION_ID);

        assertThat(result.applied()).isFalse();
        assertThat(result.warning()).contains("EMPTY_KEPT_SEGMENTS");
        verify(summaryMapper, never()).insert(any(AiConversationSummaryEntity.class));
    }

    @Test
    void beforeTurnRetriesOnceOnStateVersionConflict() {
        seedState("{}");
        AtomicInteger updateCalls = new AtomicInteger();
        when(taskStateMapper.updateById(any(AiConversationTaskStateEntity.class))).thenAnswer(invocation -> {
            if (updateCalls.incrementAndGet() == 1) {
                return 0;
            }
            stateRef.set(invocation.getArgument(0));
            return 1;
        });

        service.beforeTurn(USER_ID, CONVERSATION_ID, request("继续", null));

        assertThat(updateCalls.get()).isEqualTo(2);
        assertThat(stateRef.get().stateJson).isNotBlank();
    }

    @Test
    void beforeTurnThrowsConflictWhenStateCasKeepsFailing() {
        seedState("{}");
        when(taskStateMapper.updateById(any(AiConversationTaskStateEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> service.beforeTurn(USER_ID, CONVERSATION_ID, request("继续", null)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Conversation task state update conflict");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void afterTurnUpdatesCurrentSnapshotPointerOnConversation() {
        seedState("{}");
        when(codeSnapshotMapper.insert(any(AiCodeSnapshotEntity.class))).thenAnswer(invocation -> {
            AiCodeSnapshotEntity snapshot = invocation.getArgument(0);
            snapshot.id = 555L;
            return 1;
        });
        AiChatRequest withCode = new AiChatRequest(
                CONVERSATION_ID,
                null,
                "看看这段代码",
                "assist",
                null,
                new AiChatRequest.CodeContext("cpp", "int main(){return 0;}"),
                null
        );

        service.afterTurn(USER_ID, CONVERSATION_ID, 1L, 2L, withCode,
                new AiCompletion("好的", "mock", "mock", 1, 1), new AiChatContext("", "", "", "", ""));

        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(conversationMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("current_snapshot_id");
    }

    private static final String CLARIFICATION_QUESTION = "如果我们假设最小距离为某个值，我们如何检查这个距离是否能够满足选择 m 个星港的条件？";

    private void seedState(String stateJson) {
        AiConversationTaskStateEntity entity = new AiConversationTaskStateEntity();
        entity.id = 100L;
        entity.userId = USER_ID;
        entity.conversationId = CONVERSATION_ID;
        entity.stateJson = stateJson;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = LocalDateTime.now();
        stateRef.set(entity);
    }

    private AiCompletion.Clarification clarification(String question) {
        return new AiCompletion.Clarification(
                "clarify_check",
                "helpful",
                "检查方式",
                question,
                new AiCompletion.ClarificationInput("free_text", true, List.of(), true, "free_text", ""),
                List.of(),
                "ask_user",
                null
        );
    }

    private AiChatRequest request(String message, AiChatRequest.ClarificationAnswer answer) {
        return new AiChatRequest(CONVERSATION_ID, null, message, "hint", null, null, answer);
    }

    private AiMessageEntity message(Long id, String role, String content) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setConversationId(CONVERSATION_ID);
        message.setUserId(USER_ID);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
