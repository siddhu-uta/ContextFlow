package com.contextflow.auth.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.contextflow.auth.dto.CreateUserRequest;
import com.contextflow.auth.dto.UserResponse;
import com.contextflow.auth.security.TenantAwarePrincipal;
import com.contextflow.auth.service.TenantService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request,
                                                    Authentication authentication) {
        TenantAwarePrincipal principal = (TenantAwarePrincipal) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tenantService.createUser(principal.tenantId(), request));
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponse>> listUsers(Authentication authentication) {
        TenantAwarePrincipal principal = (TenantAwarePrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(tenantService.listUsers(principal.tenantId()));
    }
}
