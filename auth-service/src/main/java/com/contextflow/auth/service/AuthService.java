package com.contextflow.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contextflow.auth.dto.LoginRequest;
import com.contextflow.auth.dto.RegisterTenantRequest;
import com.contextflow.auth.dto.TokenResponse;
import com.contextflow.auth.entity.RefreshToken;
import com.contextflow.auth.entity.Tenant;
import com.contextflow.auth.entity.User;
import com.contextflow.auth.entity.UserRole;
import com.contextflow.auth.exception.InvalidCredentialsException;
import com.contextflow.auth.exception.InvalidTokenException;
import com.contextflow.auth.exception.TenantSlugAlreadyExistsException;
import com.contextflow.auth.repository.RefreshTokenRepository;
import com.contextflow.auth.repository.TenantRepository;
import com.contextflow.auth.repository.UserRepository;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public TokenResponse register(RegisterTenantRequest request) {
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new TenantSlugAlreadyExistsException("Slug already taken: " + request.slug());
        }

        Tenant tenant = new Tenant();
        tenant.setName(request.organizationName());
        tenant.setSlug(request.slug());
        tenant = tenantRepository.save(tenant);

        User admin = new User();
        admin.setTenant(tenant);
        admin.setEmail(request.adminEmail());
        admin.setPasswordHash(passwordEncoder.encode(request.adminPassword()));
        admin.setRole(UserRole.ADMIN);
        admin = userRepository.save(admin);

        return issueTokenPair(admin);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        return issueTokenPair(user);
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        String tokenHash = jwtService.hashRefreshToken(rawRefreshToken);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired refresh token"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Refresh token has expired or been revoked");
        }

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        return issueTokenPair(refreshToken.getUser());
    }

    public void logout(String accessToken) {
        try {
            Claims claims = jwtService.extractClaims(accessToken);
            Instant expiry = claims.getExpiration().toInstant();
            jwtService.blocklistToken(claims.getId(), expiry);

            UUID userId = UUID.fromString(claims.getSubject());
            refreshTokenRepository.revokeAllForUser(userId);
        } catch (Exception ignored) {
            // Token may already be invalid — logout is idempotent
        }
    }

    private TokenResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user);

        String rawRefreshToken = jwtService.generateRawRefreshToken();
        String tokenHash = jwtService.hashRefreshToken(rawRefreshToken);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(tokenHash);
        refreshToken.setExpiresAt(jwtService.refreshTokenExpiry());
        refreshTokenRepository.save(refreshToken);

        return TokenResponse.of(
                accessToken,
                rawRefreshToken,
                jwtService.accessTokenExpirySeconds(),
                user.getId(),
                user.getTenant().getId(),
                user.getRole().name()
        );
    }
}
