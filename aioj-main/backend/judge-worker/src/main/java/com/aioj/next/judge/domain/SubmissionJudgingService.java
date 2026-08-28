package com.aioj.next.judge.domain;

import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.persistence.entity.JudgeAuditLogEntity;
import com.aioj.next.judge.persistence.entity.SubmissionCaseResultEntity;
import com.aioj.next.judge.persistence.entity.SubmissionEntity;
import com.aioj.next.judge.persistence.mapper.JudgeAuditLogMapper;
import com.aioj.next.judge.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.judge.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Objects;

@Service
public class SubmissionJudgingService {
    private static final Logger log = LoggerFactory.getLogger(SubmissionJudgingService.class);
    private static final int MESSAGE_LIMIT = 512;
    private static final int OUTPUT_MAX_LENGTH = 8 * 1024;

    private final SubmissionMapper submissionMapper;
    private final SubmissionCaseResultMapper caseResultMapper;
    private final JudgeAuditLogMapper auditLogMapper;
    private final JudgeWorkerProperties properties;
    private final JudgedSubmissionEventClient judgedSubmissionEventClient;
    private final String workerId;

    public SubmissionJudgingService(SubmissionMapper submissionMapper,
                                    SubmissionCaseResultMapper caseResultMapper,
                                    JudgeAuditLogMapper auditLogMapper,
                                    JudgeWorkerProperties properties,
                                    JudgedSubmissionEventClient judgedSubmissionEventClient) {
        this.submissionMapper = submissionMapper;
        this.caseResultMapper = caseResultMapper;
        this.auditLogMapper = auditLogMapper;
        this.properties = properties;
        this.judgedSubmissionEventClient = judgedSubmissionEventClient;
        this.workerId = resolveWorkerId();
    }

    public boolean startRunning(JudgeTaskMessage task) {
        validateTask(task);
        SubmissionEntity update = new SubmissionEntity();
        update.setStatus(SubmissionStatus.RUNNING);
        update.setJudgeMessage("Judging started");
        update.setUpdatedAt(Instant.now());
        int updated = submissionMapper.update(update, new LambdaUpdateWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getId, task.submissionId())
                .eq(SubmissionEntity::getProblemId, task.problemId())
                .eq(SubmissionEntity::getUserId, task.userId())
                .eq(SubmissionEntity::getLanguage, task.language())
                .eq(SubmissionEntity::getStatus, SubmissionStatus.QUEUED));
        if (updated == 1) {
            audit(task.submissionId(), SubmissionStatus.QUEUED, SubmissionStatus.RUNNING, "Judging started");
            return true;
        }
        SubmissionEntity existing = submissionMapper.selectById(task.submissionId());
        if (existing != null && existing.getStatus() == SubmissionStatus.QUEUED && !matchesTask(existing, task)) {
            throw new NonRetryableJudgeTaskException("Judge task does not match queued submission");
        }
        log.info("submission={} was not QUEUED; currentStatus={}", task.submissionId(),
                existing == null ? "missing" : existing.getStatus());
        return false;
    }

    @Transactional
    public boolean finish(JudgeTaskMessage task, JudgeResult result) {
        SubmissionStatus status = result.status();
        Instant judgedAt = result.judgedAt() == null ? Instant.now() : result.judgedAt();
        SubmissionEntity update = new SubmissionEntity();
        update.setStatus(status);
        update.setJudgeMessage(truncate(result.message()));
        update.setTimeMillis(result.timeMillis());
        update.setMemoryKb(result.memoryKb());
        update.setJudgedAt(judgedAt);
        update.setUpdatedAt(judgedAt);
        update.setStdoutExcerpt(truncateOutput(result.stdout()));
        update.setStderrExcerpt(truncateOutput(result.stderr()));
        update.setExitStatus(result.exitStatus());
        update.setRunTimeMillis(result.runTimeMillis());
        update.setScore(result.score());
        update.setMaxScore(result.maxScore());
        int updated = submissionMapper.update(update, new LambdaUpdateWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getId, task.submissionId())
                .eq(SubmissionEntity::getStatus, SubmissionStatus.RUNNING));
        if (updated == 1) {
            rewriteCaseResults(task, result, judgedAt);
            Integer signalValue = status == SubmissionStatus.RUNTIME_ERROR
                    && "Signalled".equals(result.message()) ? result.exitStatus() : null;
            audit(task.submissionId(), SubmissionStatus.RUNNING, status, result.message(), signalValue, null);
            notifyJudgedAfterCommit(task, status, judgedAt);
            return true;
        }
        log.info("submission={} terminal update skipped because it is no longer RUNNING", task.submissionId());
        return false;
    }

    public void markSystemError(Long submissionId, String message) {
        if (submissionId == null) {
            return;
        }
        Instant now = Instant.now();
        SubmissionEntity update = new SubmissionEntity();
        update.setStatus(SubmissionStatus.SYSTEM_ERROR);
        update.setJudgeMessage(truncate(message));
        update.setJudgedAt(now);
        update.setUpdatedAt(now);
        int updated = submissionMapper.update(update, new LambdaUpdateWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getId, submissionId)
                .in(SubmissionEntity::getStatus, SubmissionStatus.QUEUED, SubmissionStatus.RUNNING));
        if (updated == 1) {
            audit(submissionId, null, SubmissionStatus.SYSTEM_ERROR, message);
            SubmissionEntity submission = submissionMapper.selectById(submissionId);
            if (submission != null) {
                notifyJudgedAfterCommit(new AiJudgedSubmissionEventRequest(
                        submission.getId(),
                        submission.getProblemId(),
                        submission.getUserId(),
                        SubmissionStatus.SYSTEM_ERROR,
                        submission.getLanguage(),
                        submission.getContestId(),
                        submission.getContestRunId(),
                        submission.getContestProblemId(),
                        now
                ));
            }
        }
    }

    public void validateTask(JudgeTaskMessage task) {
        if (task == null || task.submissionId() == null || task.problemId() == null || task.userId() == null) {
            throw new NonRetryableJudgeTaskException("Judge task is missing required identifiers");
        }
        if (!StringUtils.hasText(task.language()) || !properties.getLanguageWhitelist().contains(task.language())) {
            throw new NonRetryableJudgeTaskException("Judge task language is not enabled");
        }
    }

    private void audit(Long submissionId, SubmissionStatus fromStatus, SubmissionStatus toStatus, String message) {
        audit(submissionId, fromStatus, toStatus, message, null, null);
    }

    private void audit(Long submissionId, SubmissionStatus fromStatus, SubmissionStatus toStatus, String message,
                       Integer signalValue, String sandboxRunId) {
        try {
            JudgeAuditLogEntity audit = new JudgeAuditLogEntity();
            audit.setSubmissionId(submissionId);
            audit.setFromStatus(fromStatus);
            audit.setToStatus(toStatus);
            audit.setWorkerId(workerId);
            audit.setMessage(truncate(message));
            audit.setSignalValue(signalValue);
            audit.setSandboxRunId(sandboxRunId);
            audit.setCreatedAt(Instant.now());
            auditLogMapper.insert(audit);
        } catch (RuntimeException ex) {
            log.warn("Failed to write judge audit log for submission={}", submissionId, ex);
        }
    }

    private void notifyJudgedAfterCommit(JudgeTaskMessage task, SubmissionStatus status, Instant judgedAt) {
        notifyJudgedAfterCommit(new AiJudgedSubmissionEventRequest(
                task.submissionId(),
                task.problemId(),
                task.userId(),
                status,
                task.language(),
                task.contestId(),
                task.contestRunId(),
                task.contestProblemId(),
                judgedAt
        ));
    }

    private void notifyJudgedAfterCommit(AiJudgedSubmissionEventRequest request) {
        if (judgedSubmissionEventClient == null || request == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            judgedSubmissionEventClient.notifyJudgedSubmission(request);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                judgedSubmissionEventClient.notifyJudgedSubmission(request);
            }
        });
    }

    private void rewriteCaseResults(JudgeTaskMessage task, JudgeResult result, Instant createdAt) {
        caseResultMapper.delete(new LambdaQueryWrapper<SubmissionCaseResultEntity>()
                .eq(SubmissionCaseResultEntity::getSubmissionId, task.submissionId()));
        if (result.caseResults() == null || result.caseResults().isEmpty()) {
            return;
        }
        for (JudgeCaseResult caseResult : result.caseResults()) {
            SubmissionCaseResultEntity entity = new SubmissionCaseResultEntity();
            entity.setSubmissionId(task.submissionId());
            entity.setContestId(task.contestId());
            entity.setContestProblemId(task.contestProblemId());
            entity.setContestParticipantId(task.contestParticipantId());
            entity.setTestcasePackageId(caseResult.testcasePackageId());
            entity.setCaseId(caseResult.caseId());
            entity.setCaseIndex(caseResult.caseIndex());
            entity.setCaseName(truncateCaseName(caseResult.caseName()));
            entity.setSubtaskKey(caseResult.subtaskKey());
            entity.setStatus(caseResult.status());
            entity.setScore(caseResult.score());
            entity.setMaxScore(caseResult.maxScore());
            entity.setTimeMillis(caseResult.timeMillis());
            entity.setMemoryKb(caseResult.memoryKb());
            entity.setMessage(truncate(caseResult.message()));
            entity.setCreatedAt(createdAt);
            caseResultMapper.insert(entity);
        }
    }

    private String truncate(String message) {
        if (message == null || message.length() <= MESSAGE_LIMIT) {
            return message;
        }
        return message.substring(0, MESSAGE_LIMIT);
    }

    private String truncateOutput(String value) {
        if (value == null || value.length() <= OUTPUT_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, OUTPUT_MAX_LENGTH) + "\n...[truncated]";
    }

    private String truncateCaseName(String value) {
        if (value == null || value.length() <= 160) {
            return value;
        }
        return value.substring(0, 160);
    }

    private boolean matchesTask(SubmissionEntity submission, JudgeTaskMessage task) {
        return Objects.equals(submission.getProblemId(), task.problemId())
                && Objects.equals(submission.getUserId(), task.userId())
                && Objects.equals(submission.getContestId(), task.contestId())
                && Objects.equals(submission.getContestRunId(), task.contestRunId())
                && Objects.equals(submission.getContestProblemId(), task.contestProblemId())
                && Objects.equals(submission.getContestParticipantId(), task.contestParticipantId())
                && Objects.equals(submission.getLanguage(), task.language());
    }

    private String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + ManagementFactory.getRuntimeMXBean().getName();
        } catch (UnknownHostException ex) {
            return ManagementFactory.getRuntimeMXBean().getName();
        }
    }
}
