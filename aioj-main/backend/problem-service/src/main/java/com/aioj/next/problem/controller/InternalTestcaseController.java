package com.aioj.next.problem.controller;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.problem.domain.testcase.TestcasePackageService;
import com.aioj.next.problem.domain.testcase.TestcaseStorageService;
import com.aioj.next.problem.persistence.entity.TestcasePackageEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/internal/testcase-packages")
public class InternalTestcaseController {
    private final TestcasePackageService testcasePackageService;
    private final TestcaseStorageService storageService;

    public InternalTestcaseController(TestcasePackageService testcasePackageService,
                                      TestcaseStorageService storageService) {
        this.testcasePackageService = testcasePackageService;
        this.storageService = storageService;
    }

    @GetMapping("/{packageId}/blob")
    public ResponseEntity<InputStreamResource> downloadBlob(@PathVariable Long packageId) {
        TestcasePackageEntity testcasePackage = testcasePackageService.findReadyOrThrow(packageId);
        Path zipPath = storageService.resolveStorageKey(testcasePackage.getStorageKey());
        if (!Files.isRegularFile(zipPath)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "package zip not found on disk");
        }

        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(testcasePackage.getFileSizeBytes())
                    .header("X-Testcase-Sha256", testcasePackage.getSha256())
                    .header("X-Testcase-FileName", testcasePackage.getFileName())
                    .body(new InputStreamResource(Files.newInputStream(zipPath)));
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "open package zip failed: " + ex.getMessage());
        }
    }
}
