package com.contextflow.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpiryMinutes,
        long refreshTokenExpiryDays,
        String issuer
) {}
