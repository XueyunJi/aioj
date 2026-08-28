package com.aioj.next.problem;

import com.aioj.next.common.security.JwtProperties;
import com.aioj.next.problem.config.ContestProperties;
import com.aioj.next.problem.config.InternalApiProperties;
import com.aioj.next.problem.config.OperationProperties;
import com.aioj.next.problem.config.TestcaseProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.aioj.next")
@MapperScan("com.aioj.next.problem.persistence.mapper")
@EnableScheduling
@EnableConfigurationProperties({JwtProperties.class, TestcaseProperties.class, InternalApiProperties.class, OperationProperties.class,
        ContestProperties.class})
public class ProblemServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProblemServiceApplication.class, args);
    }
}
