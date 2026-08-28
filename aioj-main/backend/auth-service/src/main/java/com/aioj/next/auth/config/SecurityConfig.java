package com.aioj.next.auth.config;

import com.aioj.next.auth.domain.InMemoryLoginAttemptGuard;
import com.aioj.next.auth.domain.LoginAttemptGuard;
import com.aioj.next.common.security.BearerTokenAuthenticationFilter;
import com.aioj.next.common.security.ApiSecurityExceptionHandlers;
import com.aioj.next.common.security.JwtProperties;
import com.aioj.next.common.security.JwtTokenService;
import com.aioj.next.common.security.PasswordResetRequiredFilter;
import com.aioj.next.common.web.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    JwtTokenService jwtTokenService(JwtProperties properties) {
        return new JwtTokenService(properties);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    LoginAttemptGuard loginAttemptGuard(AuthProperties properties) {
        AuthProperties.LoginAttempt attempt = properties.getLoginAttempt();
        return new InMemoryLoginAttemptGuard(attempt.getMaxFailures(), attempt.getWindow(), attempt.getLockDuration());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService jwtTokenService) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(ApiSecurityExceptionHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(ApiSecurityExceptionHandlers.accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/auth/register", "/auth/refresh", "/auth/logout",
                                "/diagnostics/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new BearerTokenAuthenticationFilter(jwtTokenService), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new PasswordResetRequiredFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new RefreshTokenAccessGuardFilter(jwtTokenService), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
