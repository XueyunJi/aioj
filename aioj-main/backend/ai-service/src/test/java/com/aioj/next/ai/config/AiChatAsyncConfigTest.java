package com.aioj.next.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatAsyncConfigTest {
    @Test
    void problemDraftExecutorAllowsSmallQueueBehindRunningTask() throws Exception {
        AiProperties properties = new AiProperties();
        properties.getCapacity().setProblemDraftConcurrency(1);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AiChatAsyncConfig()
                .aiProblemDraftExecutor(properties);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> running = executor.submit(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            Future<?> queued = executor.submit(() -> {
            });

            release.countDown();
            running.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
