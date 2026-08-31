package com.aioj.next.problem.domain;

import com.aioj.next.contract.tutor.TutorJudgeEventRequest;
import com.aioj.next.problem.config.TutorIntegrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class TutorJudgeEventClient {
    private static final Logger log = LoggerFactory.getLogger(TutorJudgeEventClient.class);
    private final TutorIntegrationProperties properties;
    private final RestClient restClient;

    public TutorJudgeEventClient(TutorIntegrationProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getBaseUrl()))
                .defaultHeader("X-AIOJ-Webhook-Token", properties.getWebhookToken())
                .build();
    }

    public boolean notifyJudgeEvent(TutorJudgeEventRequest event) {
        if (!properties.isEnabled() || event == null || event.eventId() == null) {
            return false;
        }
        try {
            restClient.post()
                    .uri("/api/v1/tutor/integrations/aioj/judge-events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException ex) {
            log.warn("Tutor judge event delivery failed eventId={} submission={} error={}",
                    event.eventId(), event.submissionId(), ex.getClass().getSimpleName());
            return false;
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8600";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
