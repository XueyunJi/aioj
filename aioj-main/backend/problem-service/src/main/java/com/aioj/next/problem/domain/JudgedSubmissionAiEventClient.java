package com.aioj.next.problem.domain;

import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.problem.config.PlagiarismProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class JudgedSubmissionAiEventClient {
    private static final Logger log = LoggerFactory.getLogger(JudgedSubmissionAiEventClient.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;

    public JudgedSubmissionAiEventClient(PlagiarismProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getAiServiceUri()))
                .defaultHeader(INTERNAL_TOKEN_HEADER, properties.getInternalApiToken())
                .build();
    }

    public boolean notifyJudgedSubmission(AiJudgedSubmissionEventRequest request) {
        if (request == null || request.submissionId() == null) {
            return false;
        }
        try {
            restClient.post()
                    .uri("/api/v1/internal/ai/submissions/judged-events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            log.warn("AI judged submission event rejected submission={} status={} responseStatus={}",
                    request.submissionId(), request.status(), ex.getStatusCode().value());
            return false;
        } catch (RestClientException ex) {
            log.warn("AI judged submission event unavailable submission={} status={} error={}",
                    request.submissionId(), request.status(), ex.getClass().getSimpleName());
            return false;
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8204";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
