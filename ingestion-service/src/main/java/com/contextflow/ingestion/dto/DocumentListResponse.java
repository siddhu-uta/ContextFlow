package com.contextflow.ingestion.dto;

import java.time.Instant;
import java.util.UUID;

import com.contextflow.ingestion.entity.Document;
import com.contextflow.ingestion.entity.DocumentStatus;

public record DocumentListResponse(
        UUID documentId,
        UUID jobId,
        String filename,
        String contentType,
        long fileSizeBytes,
        DocumentStatus status,
        Instant createdAt
) {
    public static DocumentListResponse from(Document doc) {
        return new DocumentListResponse(
                doc.getId(),
                doc.getJobId(),
                doc.getOriginalFilename(),
                doc.getContentType(),
                doc.getFileSizeBytes() != null ? doc.getFileSizeBytes() : 0L,
                doc.getStatus(),
                doc.getCreatedAt()
        );
    }
}
