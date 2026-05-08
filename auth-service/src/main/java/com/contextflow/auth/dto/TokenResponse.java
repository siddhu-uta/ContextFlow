package com.contextflow.auth.dto;

import java.util.UUID;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        UUID tenantId,
        String role
) {
    public static TokenResponse of(String accessToken, String refreshToken,
                                   long expiresInSeconds, UUID userId,
                                   UUID tenantId, String role) {
        return new TokenResponse(accessToken, refreshToken, "Bearer",
                expiresInSeconds, userId, tenantId, role);
    }
}
