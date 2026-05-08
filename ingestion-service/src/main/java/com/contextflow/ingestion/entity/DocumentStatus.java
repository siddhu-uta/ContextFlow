package com.contextflow.ingestion.entity;

public enum DocumentStatus {
    PENDING,      // Uploaded to S3, event published
    PROCESSING,   // Embedding worker picked it up
    COMPLETED,    // Embeddings stored in pgvector
    FAILED        // Error during processing
}
