package com.aioj.next.contract.contest;

public record ContestExportResponse(
        String fileName,
        String contentType,
        String base64Content,
        long byteSize
) {
}
