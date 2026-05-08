package com.contextflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String jwtSecret,
        String authServiceUrl,
        String ingestionServiceUrl,
        String queryServiceUrl
) {}
