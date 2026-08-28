package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

final class AiHttpClients {
    private AiHttpClients() {
    }

    static RestClient create(AiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties, true)));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties, false)));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private static long timeout(AiProperties properties, boolean connect) {
        AiProperties.Http http = properties == null ? null : properties.getHttp();
        int value = http == null ? 0 : connect ? http.getConnectTimeoutMs() : http.getReadTimeoutMs();
        return Math.max(1000, value);
    }
}
