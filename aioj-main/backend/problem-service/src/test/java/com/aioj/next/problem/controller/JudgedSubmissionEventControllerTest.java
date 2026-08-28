package com.aioj.next.problem.controller;

import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.domain.JudgedSubmissionEventService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JudgedSubmissionEventControllerTest {
    @Test
    void judgedSubmissionEventDelegatesToService() {
        JudgedSubmissionEventService eventService = mock(JudgedSubmissionEventService.class);
        InternalJudgedSubmissionEventController controller =
                new InternalJudgedSubmissionEventController(eventService);
        AiJudgedSubmissionEventRequest request = new AiJudgedSubmissionEventRequest(
                123L,
                99L,
                7L,
                SubmissionStatus.ACCEPTED,
                "java",
                null,
                null,
                null,
                Instant.parse("2026-06-25T00:00:00Z")
        );

        controller.judgedSubmissionEvent(request);

        verify(eventService).handle(request);
    }
}
