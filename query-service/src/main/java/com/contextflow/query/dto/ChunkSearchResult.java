package com.contextflow.query.dto;

public record ChunkSearchResult(
        String filename,
        int chunkIndex,
        String content,
        int pageNumber,
        double similarity
) {}
