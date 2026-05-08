package com.contextflow.auth.dto;

import java.time.Instant;
import java.util.UUID;

import com.contextflow.auth.entity.User;
import com.contextflow.auth.entity.UserRole;
import com.contextflow.auth.entity.UserStatus;

public record UserResponse(
        UUID id,
        String email,
        UserRole role,
        UserStatus status,
        UUID tenantId,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getTenant().getId(),
                user.getCreatedAt()
        );
    }
}
