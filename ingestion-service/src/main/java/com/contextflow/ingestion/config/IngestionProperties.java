package com.contextflow.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
        S3Properties s3,
        JwtProperties jwt,
        KafkaTopics kafka
) {
    public record S3Properties(String endpoint, String bucket, String region,
                                String accessKey, String secretKey) {}

    public record JwtProperties(String secret, String issuer) {}

    public record KafkaTopics(String documentUploaded) {}
}
