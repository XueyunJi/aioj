package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.config.AiProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class AgentHttpClients {

    private AgentHttpClients() {
    }

    static RestClient create(AiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getHttp().getConnectTimeoutMs());
        factory.setReadTimeout(properties.getHttp().getReadTimeoutMs());
        return RestClient.builder().requestFactory(factory).build();
    }
}
