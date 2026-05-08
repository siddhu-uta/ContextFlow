package com.contextflow.ingestion.event;

import java.time.Instant;

public record DocumentUploadedEvent(
        String documentId,
        String jobId,
        String tenantId,
        String s3Key,
        String originalFilename,
        String contentType,
        long fileSizeBytes,
        Instant uploadedAt
) {}
