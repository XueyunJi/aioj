package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import org.springframework.stereotype.Service;

@Service
public class JudgedSubmissionEventService {
    private final SubmissionMapper submissionMapper;
    private final JudgedSubmissionAiEventClient aiEventClient;

    public JudgedSubmissionEventService(SubmissionMapper submissionMapper,
                                        JudgedSubmissionAiEventClient aiEventClient) {
        this.submissionMapper = submissionMapper;
        this.aiEventClient = aiEventClient;
    }

    public void handle(AiJudgedSubmissionEventRequest request) {
        if (request == null || request.submissionId() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Submission id is required");
        }
        SubmissionEntity submission = submissionMapper.selectById(request.submissionId());
        if (submission == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Submission not found");
        }
        if (submission.getStatus() == SubmissionStatus.QUEUED || submission.getStatus() == SubmissionStatus.RUNNING) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Submission is still being judged");
        }
        aiEventClient.notifyJudgedSubmission(new AiJudgedSubmissionEventRequest(
                submission.getId(),
                submission.getProblemId(),
                submission.getUserId(),
                submission.getStatus(),
                submission.getLanguage(),
                submission.getContestId(),
                submission.getContestRunId(),
                submission.getContestProblemId(),
                submission.getJudgedAt()
        ));
    }
}
