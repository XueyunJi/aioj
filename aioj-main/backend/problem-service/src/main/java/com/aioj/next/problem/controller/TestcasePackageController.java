package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.problem.TestcasePackageResponse;
import com.aioj.next.contract.problem.TestcaseUploadCompleteRequest;
import com.aioj.next.contract.problem.TestcaseUploadFailRequest;
import com.aioj.next.contract.problem.TestcaseUploadInitRequest;
import com.aioj.next.contract.problem.TestcaseUploadInitResponse;
import com.aioj.next.contract.problem.TestcaseUploadStatusResponse;
import com.aioj.next.problem.domain.testcase.TestcasePackageService;
import com.aioj.next.problem.domain.testcase.TestcaseStorageService;
import com.aioj.next.problem.persistence.entity.TestcasePackageEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/problems/{problemId}/testcase-packages")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class TestcasePackageController {
    private final TestcasePackageService testcasePackageService;
    private final TestcaseStorageService storageService;

    public TestcasePackageController(TestcasePackageService testcasePackageService,
                                     TestcaseStorageService storageService) {
        this.testcasePackageService = testcasePackageService;
        this.storageService = storageService;
    }

    @PostMapping("/init")
    public ApiResponse<TestcaseUploadInitResponse> init(@PathVariable Long problemId,
                                                        @RequestBody @Valid TestcaseUploadInitRequest request) {
        return ApiResponse.ok(testcasePackageService.init(problemId, request));
    }

    @PutMapping(value = "/uploads/{uploadId}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<TestcaseUploadStatusResponse> uploadChunk(@PathVariable Long problemId,
                                                                 @PathVariable String uploadId,
                                                                 @PathVariable int index,
                                                                 @RequestHeader(value = "X-Chunk-Sha256", required = false)
                                                                 String chunkSha256,
                                                                 HttpServletRequest request) throws IOException {
        return ApiResponse.ok(testcasePackageService.uploadChunk(problemId, uploadId, index,
                chunkSha256, request.getInputStream()));
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public ApiResponse<TestcasePackageResponse> complete(@PathVariable Long problemId,
                                                         @PathVariable String uploadId,
                                                         @RequestBody(required = false) @Valid
                                                         TestcaseUploadCompleteRequest request) {
        return ApiResponse.ok(testcasePackageService.complete(problemId, uploadId, request));
    }

    @PostMapping("/uploads/{uploadId}/fail")
    public ApiResponse<TestcaseUploadStatusResponse> fail(@PathVariable Long problemId,
                                                          @PathVariable String uploadId,
                                                          @RequestBody(required = false) @Valid
                                                          TestcaseUploadFailRequest request) {
        return ApiResponse.ok(testcasePackageService.failUpload(problemId, uploadId, request));
    }

    @GetMapping("/uploads/{uploadId}/status")
    public ApiResponse<TestcaseUploadStatusResponse> status(@PathVariable Long problemId,
                                                            @PathVariable String uploadId) {
        return ApiResponse.ok(testcasePackageService.status(problemId, uploadId));
    }

    @GetMapping
    public ApiResponse<List<TestcasePackageResponse>> list(@PathVariable Long problemId) {
        return ApiResponse.ok(testcasePackageService.list(problemId));
    }

    @PostMapping(value = "/{packageId}/cases", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TestcasePackageResponse> appendCase(@PathVariable Long problemId,
                                                           @PathVariable Long packageId,
                                                           @RequestParam("inputFile") MultipartFile inputFile,
                                                           @RequestParam("outputFile") MultipartFile outputFile,
                                                           @RequestParam("caseName") String caseName,
                                                           @RequestParam(value = "score", required = false) Integer score,
                                                           @RequestParam(value = "subtaskKey", required = false) String subtaskKey) {
        return ApiResponse.ok(testcasePackageService.appendCase(problemId, packageId, inputFile, outputFile,
                caseName, score, subtaskKey));
    }

    @PostMapping(value = "/{packageId}/cases/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TestcasePackageResponse> appendCases(@PathVariable Long problemId,
                                                            @PathVariable Long packageId,
                                                            @RequestParam("metadata") String metadata,
                                                            @RequestParam("inputFiles") List<MultipartFile> inputFiles,
                                                            @RequestParam("outputFiles") List<MultipartFile> outputFiles) {
        return ApiResponse.ok(testcasePackageService.appendCases(problemId, packageId, metadata,
                inputFiles, outputFiles));
    }

    @GetMapping("/{packageId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long problemId,
                                                        @PathVariable Long packageId) {
        TestcasePackageEntity testcasePackage = testcasePackageService.findDownloadable(problemId, packageId);
        Path packagePath = storageService.resolveStorageKey(testcasePackage.getStorageKey());
        if (!Files.isRegularFile(packagePath)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Testcase package zip not found");
        }
        try {
            String fileName = safeDownloadName(testcasePackage.getFileName());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(Files.size(packagePath))
                    .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                            String.join(", ", HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_TYPE))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment()
                                    .filename(fileName, StandardCharsets.UTF_8)
                                    .build()
                                    .toString())
                    .body(new InputStreamResource(Files.newInputStream(packagePath)));
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to open testcase package zip");
        }
    }

    @PostMapping("/{packageId}/activate")
    public ApiResponse<TestcasePackageResponse> activate(@PathVariable Long problemId, @PathVariable Long packageId) {
        return ApiResponse.ok(testcasePackageService.activate(problemId, packageId));
    }

    @PostMapping("/{packageId}/archive")
    public ApiResponse<TestcasePackageResponse> archive(@PathVariable Long problemId, @PathVariable Long packageId) {
        return ApiResponse.ok(testcasePackageService.archive(problemId, packageId));
    }

    @PostMapping("/{packageId}/restore")
    public ApiResponse<TestcasePackageResponse> restore(@PathVariable Long problemId, @PathVariable Long packageId) {
        return ApiResponse.ok(testcasePackageService.restore(problemId, packageId));
    }

    @DeleteMapping("/{packageId}")
    public ApiResponse<Void> delete(@PathVariable Long problemId, @PathVariable Long packageId) {
        testcasePackageService.softDelete(problemId, packageId);
        return ApiResponse.ok(null);
    }

    private static String safeDownloadName(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim()
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('/', '_')
                .replace('\\', '_');
        if (!StringUtils.hasText(normalized)) {
            normalized = "testcase-package.zip";
        }
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            normalized = normalized + ".zip";
        }
        return normalized;
    }
}
