package com.aioj.next.judge;

import com.aioj.next.judge.config.JudgeTestcaseProperties;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableRabbit
@SpringBootApplication(scanBasePackages = "com.aioj.next")
@MapperScan("com.aioj.next.judge.persistence.mapper")
@EnableConfigurationProperties({JudgeWorkerProperties.class, JudgeTestcaseProperties.class})
public class JudgeWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(JudgeWorkerApplication.class, args);
    }
}
