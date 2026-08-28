package com.aioj.next.auth;

import com.aioj.next.auth.config.AuthProperties;
import com.aioj.next.common.security.JwtProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.aioj.next")
@EnableConfigurationProperties({JwtProperties.class, AuthProperties.class})
@MapperScan("com.aioj.next.auth.mapper")
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
