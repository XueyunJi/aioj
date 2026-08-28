package com.aioj.next.problem.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(ContestInvitationNotificationProperties.class)
public class ContestInvitationNotificationConfig {
    @Bean
    @Qualifier("contestInvitationNotificationExecutor")
    Executor contestInvitationNotificationExecutor(ContestInvitationNotificationProperties properties) {
        int concurrency = Math.max(1, properties.getConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("contest-invitation-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Math.max(25, properties.getBatchSize() * 4));
        executor.initialize();
        return executor;
    }
}
