package com.aioj.next.problem.domain;

import com.aioj.next.contract.contest.ContestPostmortemAiStatus;
import com.aioj.next.contract.contest.ContestPostmortemReportStatus;
import com.aioj.next.contract.contest.ContestStudentPostmortemOperationJobResponse;
import com.aioj.next.contract.contest.ContestStudentPostmortemReportResponse;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.notification.UserNotificationType;
import com.aioj.next.contract.operation.OperationJobStatus;
import com.aioj.next.contract.operation.OperationJobType;
import com.aioj.next.problem.config.OperationProperties;
import com.aioj.next.problem.domain.notification.UserNotificationService;
import com.aioj.next.problem.persistence.entity.OperationJobEntity;
import com.aioj.next.problem.persistence.mapper.OperationJobArtifactMapper;
import com.aioj.next.problem.persistence.mapper.OperationJobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationJobServiceNotificationTest {
    @Mock private OperationJobMapper jobMapper;
    @Mock private OperationJobArtifactMapper artifactMapper;
    @Mock private ContestExportService contestExportService;
    @Mock private PlagiarismService plagiarismService;
    @Mock private ContestScoreboardService scoreboardService;
    @Mock private ContestPostmortemService contestPostmortemService;
    @Mock private StudentPostmortemService studentPostmortemService;
    @Mock private OperationAuditService auditService;
    @Mock private UserNotificationService notificationService;

    private OperationJobService service;

    @AfterEach
    void shutDown() {
        if (service != null) {
            service.shutdown();
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void selfCreatedStudentPostmortemCompletionCreatesTerminalNotification() throws Exception {
        OperationJobEntity job = queuedStudentPostmortem("STUDENT", null);
        when(jobMapper.selectById(9001L)).thenReturn(job);
        when(studentPostmortemService.createMyReport(301L, 401L)).thenReturn(report());
        service = service();

        service.process(9001L);

        verify(notificationService).createStudentPostmortemTerminal(eq(101L), eq(job),
                eq(UserNotificationType.STUDENT_POSTMORTEM_JOB_COMPLETED));
    }

    @Test
    void selfCreatedStudentPostmortemFailureCreatesGenericTerminalNotification() throws Exception {
        OperationJobEntity job = queuedStudentPostmortem("STUDENT", null);
        when(jobMapper.selectById(9001L)).thenReturn(job);
        when(studentPostmortemService.createMyReport(301L, 401L)).thenThrow(new IllegalStateException("provider details"));
        service = service();

        service.process(9001L);

        verify(notificationService).createStudentPostmortemTerminal(eq(101L), eq(job),
                eq(UserNotificationType.STUDENT_POSTMORTEM_JOB_FAILED));
    }

    @Test
    void teacherCreatedParticipantPostmortemDoesNotNotifyStudent() throws Exception {
        OperationJobEntity job = queuedStudentPostmortem("TEACHER", 222L);
        when(jobMapper.selectById(9001L)).thenReturn(job);
        when(studentPostmortemService.createParticipantReport(301L, 401L, 222L)).thenReturn(report());
        service = service();

        service.process(9001L);

        verify(notificationService, never()).createStudentPostmortemTerminal(any(), any(), any());
    }

    @Test
    void activeSelfServicePostmortemIsRestoredAsSafeProgressOnly() throws Exception {
        authenticate(101L, Role.STUDENT);
        OperationJobEntity participantJob = queuedStudentPostmortem("TEACHER", 222L);
        participantJob.setId(9002L);
        OperationJobEntity selfJob = queuedStudentPostmortem("STUDENT", null);
        selfJob.setStatus(OperationJobStatus.RUNNING);
        selfJob.setErrorMessage("provider diagnostic must not be exposed");
        Instant now = Instant.now();
        selfJob.setCreatedAt(now.minusSeconds(5));
        selfJob.setStartedAt(now.minusSeconds(3));
        selfJob.setUpdatedAt(now);
        when(jobMapper.selectList(any())).thenReturn(List.of(participantJob, selfJob));
        service = service();

        ContestStudentPostmortemOperationJobResponse response = service.findMyActiveStudentPostmortemJob(301L, 401L);

        assertEquals(9001L, response.id());
        assertEquals(OperationJobStatus.RUNNING, response.status());
        assertEquals(now, response.updatedAt());
    }

    @Test
    void noActiveSelfServicePostmortemReturnsNull() {
        authenticate(101L, Role.STUDENT);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        service = service();

        assertNull(service.findMyActiveStudentPostmortemJob(301L, 401L));
    }

    private OperationJobService service() {
        OperationProperties properties = new OperationProperties();
        properties.setWorkerEnabled(false);
        properties.setExecutorPoolSize(1);
        return new OperationJobService(jobMapper, artifactMapper, contestExportService, plagiarismService,
                scoreboardService, contestPostmortemService, studentPostmortemService, auditService,
                notificationService, properties, new ObjectMapper());
    }

    private OperationJobEntity queuedStudentPostmortem(String role, Long participantId) throws Exception {
        OperationJobEntity job = new OperationJobEntity();
        job.setId(9001L);
        job.setJobType(OperationJobType.GENERATE_STUDENT_POSTMORTEM);
        job.setStatus(OperationJobStatus.QUEUED);
        job.setResourceType("CONTEST_RUN");
        job.setResourceId(401L);
        job.setContestId(301L);
        job.setContestRunId(401L);
        job.setRequestedBy(101L);
        job.setAttemptCount(0);
        job.setProgressTotal(1);
        Map<String, Object> payload = new HashMap<>();
        payload.put("actorAccount", "student-101");
        payload.put("actorRoles", java.util.List.of(role));
        payload.put("participantId", participantId);
        job.setRequestJson(new ObjectMapper().writeValueAsString(payload));
        return job;
    }

    private ContestStudentPostmortemReportResponse report() {
        Instant now = Instant.now();
        return new ContestStudentPostmortemReportResponse(7001L, 301L, 401L, 222L, 101L,
                ContestPostmortemReportStatus.COMPLETED, ContestPostmortemAiStatus.COMPLETED, 101L,
                "{}", null, null, null, null, 0L, 0L, null, now, now, now, java.util.List.of());
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "student-" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
