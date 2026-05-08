package com.contextflow.auth.security;

import java.util.UUID;

public record TenantAwarePrincipal(UUID userId, UUID tenantId, String role) {}
