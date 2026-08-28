package com.aioj.next.problem.domain.postmortem;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisResponse;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmRequest;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmResponse;
import com.aioj.next.problem.config.PlagiarismProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class StudentPostmortemAiClient {
    private static final Logger log = LoggerFactory.getLogger(StudentPostmortemAiClient.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public StudentPostmortemAiClient(PlagiarismProperties properties, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getAiServiceUri()))
                .defaultHeader("X-Internal-Token", properties.getInternalApiToken())
                .build();
        this.objectMapper = objectMapper;
    }

    public StudentPostmortemAnalysisResponse analyze(StudentPostmortemAnalysisRequest request) {
        return post("/api/v1/internal/ai/student-postmortem-analysis", request, StudentPostmortemAnalysisResponse.class,
                "AI student postmortem analysis");
    }

    public StudentPostmortemWeaknessConfirmResponse confirmWeakness(StudentPostmortemWeaknessConfirmRequest request) {
        return post("/api/v1/internal/ai/student-postmortem-weakness-confirmations", request,
                StudentPostmortemWeaknessConfirmResponse.class, "AI student postmortem weakness confirmation");
    }

    private <T> T post(String uri, Object request, Class<T> type, String label) {
        try {
            String response = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return parse(response, type, label);
        } catch (RestClientResponseException ex) {
            // The internal response may include implementation details. Record no
            // body and keep the student-facing error stable and non-sensitive.
            log.warn("{} returned HTTP {}", label, ex.getStatusCode().value());
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, label + " unavailable");
        } catch (RestClientException ex) {
            log.warn("{} could not be reached: {}", label, ex.getClass().getSimpleName());
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, label + " unavailable");
        }
    }

    private <T> T parse(String response, Class<T> type, String label) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int code = root.has("code") ? root.get("code").asInt() : 0;
            if (code != 0) {
                log.warn("{} returned application error code {}", label, code);
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, label + " unavailable");
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            return objectMapper.treeToValue(data, type);
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, label + " response could not be parsed");
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8204";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

}
