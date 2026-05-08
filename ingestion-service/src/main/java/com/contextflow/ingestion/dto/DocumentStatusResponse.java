package com.contextflow.ingestion.dto;

import java.time.Instant;
import java.util.UUID;

import com.contextflow.ingestion.entity.Document;
import com.contextflow.ingestion.entity.DocumentStatus;

public record DocumentStatusResponse(
        UUID documentId,
        UUID jobId,
        String filename,
        DocumentStatus status,
        String errorMessage,
        long fileSizeBytes,
        Instant createdAt,
        Instant processedAt
) {
    public static DocumentStatusResponse from(Document doc) {
        return new DocumentStatusResponse(
                doc.getId(),
                doc.getJobId(),
                doc.getOriginalFilename(),
                doc.getStatus(),
                doc.getErrorMessage(),
                doc.getFileSizeBytes() != null ? doc.getFileSizeBytes() : 0L,
                doc.getCreatedAt(),
                doc.getProcessedAt()
        );
    }
}
