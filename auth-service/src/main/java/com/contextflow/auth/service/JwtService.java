package com.contextflow.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.contextflow.auth.config.JwtProperties;
import com.contextflow.auth.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String BLOCKLIST_PREFIX = "jwt:blocklist:";

    private final JwtProperties jwtProperties;
    private final StringRedisTemplate redisTemplate;

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.accessTokenExpiryMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("tenantId", user.getTenant().getId().toString())
                .claim("role", user.getRole().name())
                .claim("email", user.getEmail())
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey())
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenBlocklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLOCKLIST_PREFIX + jti));
    }

    public void blocklistToken(String jti, Instant expiry) {
        long ttlSeconds = Instant.now().until(expiry, ChronoUnit.SECONDS);
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set(BLOCKLIST_PREFIX + jti, "1", ttlSeconds, TimeUnit.SECONDS);
        }
    }

    public String generateRawRefreshToken() {
        return UUID.randomUUID() + "-" + UUID.randomUUID();
    }

    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public long accessTokenExpirySeconds() {
        return jwtProperties.accessTokenExpiryMinutes() * 60;
    }

    public Instant refreshTokenExpiry() {
        return Instant.now().plus(jwtProperties.refreshTokenExpiryDays(), ChronoUnit.DAYS);
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return !isTokenBlocklisted(claims.getId());
        } catch (JwtException e) {
            return false;
        }
    }
}
