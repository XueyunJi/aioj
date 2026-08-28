package com.aioj.next.judge.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Component
public class TestcaseBlobClient {
    private final RestClient restClient;

    public TestcaseBlobClient(JudgeWorkerProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getProblemServiceBaseUrl())
                .defaultHeader("X-Internal-Token", properties.getInternalApiToken())
                .build();
    }

    public BlobHeaders downloadTo(Long packageId, Path destPath) {
        return restClient.get()
                .uri("/api/v1/internal/testcase-packages/{id}/blob", packageId)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                                "Failed to fetch testcase blob: HTTP " + response.getStatusCode());
                    }

                    String sha256 = Optional.ofNullable(response.getHeaders().getFirst("X-Testcase-Sha256"))
                            .orElse("");
                    String fileName = Optional.ofNullable(response.getHeaders().getFirst("X-Testcase-FileName"))
                            .orElse("");
                    try {
                        Files.createDirectories(destPath.getParent());
                        try (InputStream input = response.getBody()) {
                            Files.copy(input, destPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException ex) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                                "Failed to write testcase blob: " + ex.getMessage());
                    }
                    return new BlobHeaders(sha256, fileName);
                });
    }

    public record BlobHeaders(String sha256, String fileName) {
    }
}
