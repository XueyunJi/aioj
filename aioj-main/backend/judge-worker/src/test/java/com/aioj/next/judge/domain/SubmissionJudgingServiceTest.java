package com.aioj.next.judge.domain;

import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.persistence.entity.JudgeAuditLogEntity;
import com.aioj.next.judge.persistence.entity.SubmissionEntity;
import com.aioj.next.judge.persistence.mapper.JudgeAuditLogMapper;
import com.aioj.next.judge.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.judge.persistence.mapper.SubmissionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionJudgingServiceTest {
    private final SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
    private final SubmissionCaseResultMapper caseResultMapper = mock(SubmissionCaseResultMapper.class);
    private final JudgeAuditLogMapper auditLogMapper = mock(JudgeAuditLogMapper.class);
    private final JudgedSubmissionEventClient eventClient = mock(JudgedSubmissionEventClient.class);
    private final SubmissionJudgingService service = new SubmissionJudgingService(
            submissionMapper,
            caseResultMapper,
            auditLogMapper,
            new JudgeWorkerProperties(),
            eventClient
    );

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void finishNotifiesJudgedSubmissionAfterCommit() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenReturn(1);
        JudgeTaskMessage task = task();
        JudgeResult result = new JudgeResult(
                SubmissionStatus.WRONG_ANSWER,
                "Wrong answer",
                10L,
                1024L,
                Instant.parse("2026-06-25T00:00:00Z"),
                "stdout that must not be sent",
                "stderr that must not be sent",
                0,
                10L,
                null,
                null,
                List.of()
        );
        TransactionSynchronizationManager.initSynchronization();

        boolean updated = service.finish(task, result);

        assertThat(updated).isTrue();
        verify(eventClient, never()).notifyJudgedSubmission(any());
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        ArgumentCaptor<AiJudgedSubmissionEventRequest> captor =
                ArgumentCaptor.forClass(AiJudgedSubmissionEventRequest.class);
        verify(eventClient).notifyJudgedSubmission(captor.capture());
        assertThat(captor.getValue().submissionId()).isEqualTo(123L);
        assertThat(captor.getValue().problemId()).isEqualTo(99L);
        assertThat(captor.getValue().userId()).isEqualTo(7L);
        assertThat(captor.getValue().status()).isEqualTo(SubmissionStatus.WRONG_ANSWER);
        assertThat(captor.getValue().language()).isEqualTo("cpp");
    }

    @Test
    void finishSkippedUpdateDoesNotNotify() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenReturn(0);

        boolean updated = service.finish(task(), JudgeResult.systemError("Sandbox unavailable"));

        assertThat(updated).isFalse();
        verify(eventClient, never()).notifyJudgedSubmission(any());
    }

    @Test
    void systemErrorNotifiesWithDatabaseSubmissionFields() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenReturn(1);
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(123L);
        submission.setProblemId(99L);
        submission.setUserId(7L);
        submission.setLanguage("python");
        submission.setContestId(1L);
        submission.setContestRunId(2L);
        submission.setContestProblemId(3L);
        when(submissionMapper.selectById(123L)).thenReturn(submission);

        service.markSystemError(123L, "sandbox error");

        ArgumentCaptor<AiJudgedSubmissionEventRequest> captor =
                ArgumentCaptor.forClass(AiJudgedSubmissionEventRequest.class);
        verify(eventClient).notifyJudgedSubmission(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        assertThat(captor.getValue().contestRunId()).isEqualTo(2L);
        verify(auditLogMapper).insert(any(JudgeAuditLogEntity.class));
    }

    private JudgeTaskMessage task() {
        return new JudgeTaskMessage(
                123L,
                99L,
                7L,
                1L,
                2L,
                3L,
                4L,
                null,
                "cpp",
                "trace",
                1000,
                262144L
        );
    }
}
