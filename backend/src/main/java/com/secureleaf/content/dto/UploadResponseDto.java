package com.secureleaf.content.dto;

/**
 * Response body for POST /api/products/{productId}/document (HTTP 202 Accepted).
 *
 * We return 202 instead of 200 because the work is not done yet —
 * the document is queued for async processing. The client should poll
 * myProducts to watch status go PROCESSING → LIVE.
 */
public record UploadResponseDto(Long documentVersionId, Long jobId, String status) {}
