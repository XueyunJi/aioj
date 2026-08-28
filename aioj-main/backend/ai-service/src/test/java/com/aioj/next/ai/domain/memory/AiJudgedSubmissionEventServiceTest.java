package com.aioj.next.ai.domain.memory;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiJudgedSubmissionEventServiceTest {
    private final AiMemoryEventService eventService = mock(AiMemoryEventService.class);
    private final AiJudgedSubmissionEventService service = new AiJudgedSubmissionEventService(eventService);

    @Test
    void recordJudgedSubmissionEnqueuesAnalysisJobIdempotently() {
        AiJudgedSubmissionEventRequest request = new AiJudgedSubmissionEventRequest(
                123L,
                99L,
                7L,
                SubmissionStatus.TIME_LIMIT_EXCEEDED,
                "python",
                1L,
                2L,
                3L,
                Instant.parse("2026-06-25T00:00:00Z")
        );

        service.recordJudgedSubmission(request);

        ArgumentCaptor<List<AiMemoryEventService.EventJobSpec>> jobsCaptor = ArgumentCaptor.forClass(List.class);
        verify(eventService).recordEvent(
                eq(AiMemoryJobTypes.EVENT_SUBMISSION_JUDGED_SAFE),
                eq(7L),
                eq("submission"),
                eq("123"),
                eq("submission-judged-safe:123"),
                any(),
                eq(AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE),
                jobsCaptor.capture()
        );
        assertThat(jobsCaptor.getValue()).singleElement().satisfies(job -> {
            assertThat(job.jobType()).isEqualTo(AiMemoryJobTypes.JOB_AI_JUDGED_SUBMISSION_ANALYSIS);
            assertThat(job.idempotencyKey()).isEqualTo("judged-submission-analysis:123");
        });
    }

    @Test
    void queuedSubmissionIsRejected() {
        AiJudgedSubmissionEventRequest request = new AiJudgedSubmissionEventRequest(
                123L,
                99L,
                7L,
                SubmissionStatus.QUEUED,
                "cpp",
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> service.recordJudgedSubmission(request))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("terminal");
    }
}
