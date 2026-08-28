package com.aioj.next.problem.domain.postmortem;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisResponse;
import com.aioj.next.problem.config.PlagiarismProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ContestPostmortemAiAnalysisClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ContestPostmortemAiAnalysisClient(PlagiarismProperties properties, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getAiServiceUri()))
                .defaultHeader("X-Internal-Token", properties.getInternalApiToken())
                .build();
        this.objectMapper = objectMapper;
    }

    public ContestPostmortemAnalysisResponse analyze(ContestPostmortemAnalysisRequest request) {
        try {
            String response = restClient.post()
                    .uri("/api/v1/internal/ai/contest-postmortem-analysis")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return parse(response);
        } catch (RestClientResponseException ex) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "AI postmortem analysis failed: " + summarize(ex.getResponseBodyAsString()));
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "AI postmortem analysis unavailable");
        }
    }

    private ContestPostmortemAnalysisResponse parse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int code = root.has("code") ? root.get("code").asInt() : 0;
            if (code != 0) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                        root.has("message") ? root.get("message").asText() : "AI postmortem analysis rejected request");
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            return objectMapper.treeToValue(data, ContestPostmortemAnalysisResponse.class);
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "AI postmortem analysis response could not be parsed");
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8204";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "empty response";
        }
        return value.length() <= 240 ? value : value.substring(0, 240);
    }
}
