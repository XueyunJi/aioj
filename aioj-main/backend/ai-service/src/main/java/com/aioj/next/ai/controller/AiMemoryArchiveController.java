package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.memory.AiMemoryMarkdownArchiveService;
import com.aioj.next.common.security.SecuritySupport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
public class AiMemoryArchiveController {
    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

    private final AiMemoryMarkdownArchiveService archiveService;

    public AiMemoryArchiveController(AiMemoryMarkdownArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @GetMapping("/ai/memories/export/markdown")
    public ResponseEntity<byte[]> exportMarkdownArchive() {
        AiMemoryMarkdownArchiveService.MarkdownArchive archive = archiveService.export(SecuritySupport.currentUserId());
        byte[] body = archive.markdown().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(archive.fileName()))
                .body(body);
    }

    private String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fileName.replace("\"", "") + "\"; filename*=UTF-8''" + encoded;
    }
}
