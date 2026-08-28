package com.aioj.next.ai;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.common.security.JwtProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.aioj.next")
@EnableConfigurationProperties({JwtProperties.class, AiProperties.class, InternalApiProperties.class})
@MapperScan("com.aioj.next.ai.persistence.mapper")
@EnableScheduling
public class AiServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
