package com.contextflow.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "query")
public record QueryProperties(
        String openAiApiKey,
        String openAiModel,
        String embeddingWorkerUrl,
        int topK,
        int cacheTtlMinutes,
        String llmMode,   // "openai" | "mock"
        String jwtSecret
) {}
