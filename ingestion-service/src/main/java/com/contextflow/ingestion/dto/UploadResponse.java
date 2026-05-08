package com.contextflow.ingestion.dto;

import java.util.UUID;

import com.contextflow.ingestion.entity.DocumentStatus;

public record UploadResponse(
        UUID documentId,
        UUID jobId,
        String filename,
        long fileSizeBytes,
        DocumentStatus status
) {}
