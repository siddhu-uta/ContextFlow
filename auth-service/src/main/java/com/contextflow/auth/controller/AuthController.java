package com.contextflow.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.contextflow.auth.dto.LoginRequest;
import com.contextflow.auth.dto.RefreshTokenRequest;
import com.contextflow.auth.dto.RegisterTenantRequest;
import com.contextflow.auth.dto.TokenResponse;
import com.contextflow.auth.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterTenantRequest request,
                                                   HttpServletRequest httpRequest) {
        TokenResponse response = authService.register(request);
        log.info("AUDIT register org={} email={} ip={}",
                request.slug(), request.adminEmail(), httpRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest) {
        TokenResponse response = authService.login(request);
        log.info("AUDIT login email={} ip={}", request.email(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request,
                                                  HttpServletRequest httpRequest) {
        TokenResponse response = authService.refresh(request.refreshToken());
        log.info("AUDIT token_refresh ip={}", httpRequest.getRemoteAddr());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authHeader,
                                        Authentication authentication,
                                        HttpServletRequest httpRequest) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authService.logout(authHeader.substring(7));
        }
        String principal = authentication != null ? authentication.getName() : "anonymous";
        log.info("AUDIT logout principal={} ip={}", principal, httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
