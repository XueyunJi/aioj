package com.aioj.next.judge.consumer;

import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.judge.config.JudgeQueueConfig;
import com.aioj.next.judge.domain.JudgeResult;
import com.aioj.next.judge.domain.NonRetryableJudgeTaskException;
import com.aioj.next.judge.domain.SandboxClient;
import com.aioj.next.judge.domain.SubmissionJudgingService;
import com.aioj.next.judge.domain.TestcasePackageCache;
import com.aioj.next.judge.domain.TestcasePackageUnavailableException;
import com.aioj.next.judge.monitor.JudgeRuntimeMonitor;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Component
public class JudgeTaskListener {
    private static final Logger log = LoggerFactory.getLogger(JudgeTaskListener.class);

    private final SandboxClient sandboxClient;
    private final SubmissionJudgingService judgingService;
    private final TestcasePackageCache testcasePackageCache;
    private final JudgeRuntimeMonitor runtimeMonitor;

    public JudgeTaskListener(SandboxClient sandboxClient, SubmissionJudgingService judgingService,
                             TestcasePackageCache testcasePackageCache, JudgeRuntimeMonitor runtimeMonitor) {
        this.sandboxClient = sandboxClient;
        this.judgingService = judgingService;
        this.testcasePackageCache = testcasePackageCache;
        this.runtimeMonitor = runtimeMonitor;
    }

    @RabbitListener(queues = JudgeQueueConfig.JUDGE_QUEUE)
    public void onMessage(JudgeTaskMessage task, Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        long startedNanos = System.nanoTime();
        Long queueWaitMs = queueWaitMillis(message);
        try {
            if (!judgingService.startRunning(task)) {
                log.info("Acking duplicate or already handled judge task submission={} queueWaitMs={}",
                        task == null ? null : task.submissionId(), queueWaitMs);
                channel.basicAck(deliveryTag, false);
                return;
            }
            log.info("Judge task started submission={} problem={} queueWaitMs={}",
                    task.submissionId(), task.problemId(), queueWaitMs);
            runtimeMonitor.recordStarted(queueWaitMs);
            try {
                testcasePackageCache.prepareActivePackage(task.problemId())
                        .ifPresent(testcasePackage -> log.info("Prepared testcase package submission={} package={} cases={}",
                                task.submissionId(), testcasePackage.packageId(), testcasePackage.cases().size()));
            } catch (TestcasePackageUnavailableException ex) {
                String messageText = "Testcase package unavailable: " + ex.getMessage();
                log.warn("submission={} problem={} {} queueWaitMs={} judgeWallMs={}",
                        task.submissionId(), task.problemId(), messageText, queueWaitMs, elapsedMillis(startedNanos));
                judgingService.finish(task, JudgeResult.systemError(messageText));
                channel.basicAck(deliveryTag, false);
                return;
            }
            judgingService.markRunningPhase(task.submissionId());
            var result = sandboxClient.judge(task);
            judgingService.finish(task, result);
            log.info("submission={} problem={} status={} time={}ms memory={}kb queueWaitMs={} judgeWallMs={}",
                    task.submissionId(), task.problemId(), result.status(), result.timeMillis(), result.memoryKb(),
                    queueWaitMs, elapsedMillis(startedNanos));
            log.info("Acking judged submission={}", task.submissionId());
            channel.basicAck(deliveryTag, false);
            runtimeMonitor.recordCompleted(result.status().name());
        } catch (NonRetryableJudgeTaskException ex) {
            Long submissionId = task == null ? null : task.submissionId();
            log.warn("Rejecting non-retryable judge task submission={} queueWaitMs={} judgeWallMs={}: {}",
                    submissionId, queueWaitMs, elapsedMillis(startedNanos), ex.getMessage());
            judgingService.markSystemError(submissionId, ex.getMessage());
            channel.basicNack(deliveryTag, false, false);
            runtimeMonitor.recordFailure(ex);
        } catch (Exception ex) {
            Long submissionId = task == null ? null : task.submissionId();
            log.error("Judge failed submission={} problem={} user={} language={} queueWaitMs={} judgeWallMs={} errorType={} message={}; sending to DLQ",
                    submissionId, task == null ? null : task.problemId(), task == null ? null : task.userId(),
                    task == null ? null : task.language(), queueWaitMs, elapsedMillis(startedNanos),
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private Long queueWaitMillis(Message message) {
        Date timestamp = message.getMessageProperties().getTimestamp();
        if (timestamp == null) {
            return null;
        }
        return Math.max(0L, System.currentTimeMillis() - timestamp.getTime());
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
