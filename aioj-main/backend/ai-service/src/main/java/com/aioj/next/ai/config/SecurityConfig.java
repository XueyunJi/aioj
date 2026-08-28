package com.aioj.next.ai.config;

import com.aioj.next.common.security.ApiSecurityExceptionHandlers;
import com.aioj.next.common.security.BearerTokenAuthenticationFilter;
import com.aioj.next.common.security.InternalApiTokenFilter;
import com.aioj.next.common.security.JwtProperties;
import com.aioj.next.common.security.JwtTokenService;
import com.aioj.next.common.security.PasswordResetRequiredFilter;
import com.aioj.next.common.web.TraceIdFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
    TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService jwtTokenService,
                                            InternalApiProperties internalApiProperties) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(ApiSecurityExceptionHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(ApiSecurityExceptionHandlers.accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        // SSE endpoints return StreamingResponseBody: the container re-dispatches
                        // the request (ASYNC/ERROR) after the body finishes, and those continuations
                        // of an already-authenticated request must not be rejected by AuthorizationFilter.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/internal/**").hasRole("INTERNAL")
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new InternalApiTokenFilter(internalApiProperties.getApiToken()),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new BearerTokenAuthenticationFilter(jwtTokenService), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new PasswordResetRequiredFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
