package com.contextflow.query.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.contextflow.query.config.QueryProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticCacheService {

    private static final String CACHE_PREFIX = "query:cache:";

    private final StringRedisTemplate redisTemplate;
    private final QueryProperties properties;

    public Optional<String> get(String tenantId, String question) {
        String key = cacheKey(tenantId, question);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.info("Semantic cache HIT for tenant={}", tenantId);
        }
        return Optional.ofNullable(cached);
    }

    public void put(String tenantId, String question, String answer) {
        if (answer == null || answer.isBlank()) return;
        String key = cacheKey(tenantId, question);
        redisTemplate.opsForValue().set(key, answer, Duration.ofMinutes(properties.cacheTtlMinutes()));
        log.debug("Cached answer for tenant={} key={}", tenantId, key);
    }

    private String cacheKey(String tenantId, String question) {
        String normalized = question.trim().toLowerCase().replaceAll("\\s+", " ");
        String input = tenantId + "|" + normalized;
        return CACHE_PREFIX + sha256(input);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
