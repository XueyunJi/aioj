package com.aioj.next.problem.domain;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.problem.persistence.entity.OperationAuditEventEntity;
import com.aioj.next.problem.persistence.mapper.OperationAuditEventMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationAuditServiceTest {
    @Mock
    private OperationAuditEventMapper auditEventMapper;

    private OperationAuditService service;

    @BeforeEach
    void setUp() {
        service = new OperationAuditService(auditEventMapper, new ObjectMapper());
        authenticate(99L, Role.ADMIN);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listReturnsReadableChineseActionName() {
        when(auditEventMapper.selectPage(any(), any())).thenReturn(page(List.of(event("SUBMISSION_CODE_ACCESS"))));

        var result = service.list(1, 20, null, null, null, null, null);

        assertEquals(1, result.total());
        assertEquals("SUBMISSION_CODE_ACCESS", result.records().get(0).action());
        assertEquals("查看提交源码", result.records().get(0).actionDisplayName());
    }

    @Test
    void unknownActionUsesSafeDisplayName() {
        when(auditEventMapper.selectPage(any(), any())).thenReturn(page(List.of(event("RAW_UNMAPPED_CONSTANT"))));

        var result = service.list(1, 20, null, null, null, null, null);

        assertEquals("RAW_UNMAPPED_CONSTANT", result.records().get(0).action());
        assertEquals("未识别审计操作", result.records().get(0).actionDisplayName());
    }

    @Test
    void listMapsAiProblemDraftLifecycleActionsToReadableNames() {
        when(auditEventMapper.selectPage(any(), any())).thenReturn(page(List.of(
                event("PROBLEM_DRAFT_GENERATION_JOB_COMPLETED"),
                event("PROBLEM_DRAFT_REGENERATION_JOB_FAILED"),
                event("PROBLEM_DRAFT_GENERATION_JOB_CREATED")
        )));

        var result = service.list(1, 20, null, null, null, null, null);

        assertEquals("AI 题目草稿生成完成", result.records().get(0).actionDisplayName());
        assertEquals("AI 题目草稿改写失败", result.records().get(1).actionDisplayName());
        assertNotEquals("未识别审计操作", result.records().get(2).actionDisplayName());
    }

    private Page<OperationAuditEventEntity> page(List<OperationAuditEventEntity> records) {
        Page<OperationAuditEventEntity> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private OperationAuditEventEntity event(String action) {
        OperationAuditEventEntity event = new OperationAuditEventEntity();
        event.setId(101L);
        event.setActorUserId(99L);
        event.setAction(action);
        event.setResourceType("SUBMISSION");
        event.setResourceId(801L);
        event.setStatus("SUCCESS");
        event.setTraceId("trace-1");
        event.setSummaryJson("{\"auditLogId\":2070348621785022500}");
        event.setCreatedAt(Instant.parse("2026-06-26T01:00:00Z"));
        return event;
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
