package com.aioj.next.ai.agent.policy;

import com.aioj.next.ai.domain.AiTurnService;
import com.aioj.next.ai.domain.OperationAuditWriter;
import com.aioj.next.ai.domain.response.GuardDecisionAuditItem;
import com.aioj.next.ai.domain.response.GuardTurnMessagesResponse;
import com.aioj.next.ai.persistence.entity.AiGuardDecisionEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.mapper.AiGuardDecisionMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuardDecisionAuditServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private AiGuardDecisionMapper guardDecisionMapper;
    @Mock
    private AiTurnService aiTurnService;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private OperationAuditWriter auditWriter;

    private GuardDecisionAuditService service;

    @BeforeEach
    void setUp() {
        service = new GuardDecisionAuditService(
                guardDecisionMapper, aiTurnService, messageMapper, auditWriter, OBJECT_MAPPER);
        lenient().when(guardDecisionMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        authenticate(42L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listAppliesAllFiltersAndReturnsParsedJson() {
        AiGuardDecisionEntity entity = row(7701L, "L4_OUTPUT", "REFUSE");
        when(guardDecisionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(entity));

        PageResponse<GuardDecisionAuditItem> result = service.list(
                7701L, 7L, "L4_OUTPUT", "REFUSE", true,
                "2026-08-01T00:00:00Z", "2026-08-09T00:00:00Z", 2, 50);

        ArgumentCaptor<QueryWrapper<AiGuardDecisionEntity>> captor = queryCaptor();
        verify(guardDecisionMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        String custom = captor.getValue().getCustomSqlSegment();
        assertThat(sql).contains("contest_run_id").contains("user_id").contains("layer")
                .contains("decision").contains("degraded").contains("created_at");
        assertThat(sql).contains("ORDER BY created_at DESC").contains("id DESC");
        assertThat(custom).contains("LIMIT 50 OFFSET 50");
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(2L);
        assertThat(result.pageSize()).isEqualTo(50L);
        GuardDecisionAuditItem item = result.records().get(0);
        assertThat(item.id()).isEqualTo(String.valueOf(entity.getId()));
        assertThat(item.contestRunId()).isEqualTo(7701L);
        assertThat(item.matchedProblemRefs().isArray()).isTrue();
        assertThat(item.matchedProblemRefs().get(0).get("contestRunId").asLong()).isEqualTo(7701L);
        assertThat(item.detail().get("similarity").asDouble()).isEqualTo(0.91);
    }

    @Test
    void listClampsPageSizeAndDefaultsPage() {
        when(guardDecisionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        PageResponse<GuardDecisionAuditItem> result = service.list(
                null, null, null, null, null, null, null, 0, 10_000);

        ArgumentCaptor<QueryWrapper<AiGuardDecisionEntity>> captor = queryCaptor();
        verify(guardDecisionMapper).selectList(captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).contains("LIMIT 100 OFFSET 0");
        assertThat(result.page()).isEqualTo(1L);
        assertThat(result.pageSize()).isEqualTo(100L);
    }

    @Test
    void listSortsByCreatedAtDescThenIdDesc() {
        when(guardDecisionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.list(null, null, null, null, null, null, null, 1, 20);

        ArgumentCaptor<QueryWrapper<AiGuardDecisionEntity>> captor = queryCaptor();
        verify(guardDecisionMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("ORDER BY created_at DESC").contains("id DESC");
    }

    @Test
    void listRejectsInvalidLayer() {
        assertThatThrownBy(() -> service.list(null, null, "L9_NOPE", null, null, null, null, 1, 20))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void listRejectsInvalidDecision() {
        assertThatThrownBy(() -> service.list(null, null, null, "MAYBE", null, null, null, 1, 20))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void listRejectsUnparseableTime() {
        assertThatThrownBy(() -> service.list(null, null, null, null, null, "not-a-time", null, 1, 20))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, "2026-13-40T99:99", 1, 20))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void listRejectsFromAfterTo() {
        assertThatThrownBy(() -> service.list(null, null, null, null, null,
                "2026-08-09T00:00:00Z", "2026-08-01T00:00:00Z", 1, 20))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void turnMessagesReturnsTurnAndBothMessages() {
        AiTurnEntity turn = turn("t-1", "1001", "1002");
        when(aiTurnService.findById("t-1")).thenReturn(turn);
        AiMessageEntity userMessage = message(1001L, "user", "这题怎么做", null);
        AiMessageEntity assistantMessage = message(1002L, "assistant", "先读题面。", "deepseek-chat");
        when(messageMapper.selectById(1001L)).thenReturn(userMessage);
        when(messageMapper.selectById(1002L)).thenReturn(assistantMessage);

        GuardTurnMessagesResponse response = service.turnMessages("t-1");

        assertThat(response.turnId()).isEqualTo("t-1");
        assertThat(response.conversationId()).isEqualTo("c-1");
        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.userMessage().id()).isEqualTo("1001");
        assertThat(response.userMessage().content()).isEqualTo("这题怎么做");
        assertThat(response.assistantMessage().id()).isEqualTo("1002");
        assertThat(response.assistantMessage().model()).isEqualTo("deepseek-chat");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(
                eq("AI_GUARD_TURN_MESSAGES_VIEW"),
                eq("AI_GUARD_TURN"),
                isNull(),
                eq("VIEWED"),
                summaryCaptor.capture(),
                eq(42L),
                isNull(),
                isNull(),
                eq(7L)
        );
        assertThat(summaryCaptor.getValue()).containsEntry("turnId", "t-1");
    }

    @Test
    void turnMessagesThrowsNotFoundForMissingTurn() {
        when(aiTurnService.findById("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.turnMessages("ghost"))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void turnMessagesToleratesTurnWithoutMessages() {
        AiTurnEntity turn = turn("t-2", null, null);
        when(aiTurnService.findById("t-2")).thenReturn(turn);

        GuardTurnMessagesResponse response = service.turnMessages("t-2");

        assertThat(response.userMessage()).isNull();
        assertThat(response.assistantMessage()).isNull();
        assertThat(response.userId()).isNull();
    }

    private static AiGuardDecisionEntity row(Long contestRunId, String layer, String decision) {
        AiGuardDecisionEntity entity = new AiGuardDecisionEntity();
        entity.setId(990011223344556677L);
        entity.setTurnId("t-1");
        entity.setUserId(7L);
        entity.setConversationId("c-1");
        entity.setContestRunId(contestRunId);
        entity.setLayer(layer);
        entity.setDecision(decision);
        entity.setMatchedProblemRefs("[{\"problemId\":1001,\"contestRunId\":7701,\"visibility\":\"PRIVATE\"}]");
        entity.setReasonCode("private_contest_problem");
        entity.setDetailJson("{\"similarity\":0.91}");
        entity.setDegraded(true);
        entity.setLatencyMs(12);
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 8, 10, 0));
        return entity;
    }

    private static AiTurnEntity turn(String id, String userMessageId, String assistantMessageId) {
        AiTurnEntity turn = new AiTurnEntity();
        turn.setId(id);
        turn.setConversationId("c-1");
        turn.setStatus("COMPLETED");
        turn.setUserMessageId(userMessageId);
        turn.setAssistantMessageId(assistantMessageId);
        turn.setCreatedAt(LocalDateTime.of(2026, 8, 8, 10, 0));
        return turn;
    }

    private static AiMessageEntity message(Long id, String role, String content, String model) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setConversationId("c-1");
        message.setUserId(7L);
        message.setRole(role);
        message.setContent(content);
        message.setModel(model);
        message.setCreatedAt(LocalDateTime.of(2026, 8, 8, 10, 0));
        return message;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<QueryWrapper<AiGuardDecisionEntity>> queryCaptor() {
        return ArgumentCaptor.forClass(QueryWrapper.class);
    }

    private static void authenticate(Long userId) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "teacher", Set.of(Role.TEACHER));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
