package com.contextflow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterTenantRequest(

        @NotBlank(message = "Organization name is required")
        @Size(min = 2, max = 255)
        String organizationName,

        @NotBlank(message = "Slug is required")
        @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug must be lowercase alphanumeric with hyphens")
        @Size(min = 3, max = 100)
        String slug,

        @NotBlank(message = "Admin email is required")
        @Email(message = "Must be a valid email address")
        String adminEmail,

        @NotBlank(message = "Admin password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String adminPassword
) {}
