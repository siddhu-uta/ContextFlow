package com.contextflow.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.contextflow.query.config.QueryProperties;

@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SemanticCacheService cacheService;

    @BeforeEach
    void setUp() {
        QueryProperties props = new QueryProperties(null, null, null, 5, 60, "mock",
                "test-secret-must-be-at-least-32-chars!!");
        cacheService = new SemanticCacheService(redisTemplate, props);
    }

    // ── Cache miss ────────────────────────────────────────────────────────────

    @Test
    void get_keyAbsent_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);

        Optional<String> result = cacheService.get("tenant-1", "What is RAG?");

        assertThat(result).isEmpty();
    }

    // ── Cache hit ─────────────────────────────────────────────────────────────

    @Test
    void get_keyPresent_returnsCachedAnswer() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("Cached answer text");

        Optional<String> result = cacheService.get("tenant-1", "What is RAG?");

        assertThat(result).hasValue("Cached answer text");
    }

    // ── Put ───────────────────────────────────────────────────────────────────

    @Test
    void put_validAnswer_storesWithConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        cacheService.put("tenant-1", "What is RAG?", "RAG stands for Retrieval-Augmented Generation.");

        verify(valueOps).set(any(String.class), eq("RAG stands for Retrieval-Augmented Generation."),
                eq(Duration.ofMinutes(60)));
    }

    @Test
    void put_blankAnswer_skipsWrite() {
        cacheService.put("tenant-1", "What is RAG?", "   ");
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void put_nullAnswer_skipsWrite() {
        cacheService.put("tenant-1", "What is RAG?", null);
        verifyNoInteractions(redisTemplate);
    }

    // ── Key determinism & normalisation ──────────────────────────────────────

    @Test
    void get_sameQuestionDifferentCasing_hitsTheSameKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("answer");

        // Both calls should compute an identical cache key
        cacheService.get("tenant-1", "What is RAG?");
        cacheService.get("tenant-1", "WHAT IS RAG?");

        // Verify the same Redis key was used both times
        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).get(keyCaptor.capture());
        assertThat(keyCaptor.getAllValues().get(0))
                .isEqualTo(keyCaptor.getAllValues().get(1));
    }

    @Test
    void get_extraWhitespaceNormalized_hitsTheSameKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("answer");

        cacheService.get("tenant-1", "what  is   rag?");
        cacheService.get("tenant-1", "what is rag?");

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).get(keyCaptor.capture());
        assertThat(keyCaptor.getAllValues().get(0))
                .isEqualTo(keyCaptor.getAllValues().get(1));
    }

    @Test
    void get_differentTenants_differentCacheKeys() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("answer");

        cacheService.get("tenant-a", "same question");
        cacheService.get("tenant-b", "same question");

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).get(keyCaptor.capture());
        assertThat(keyCaptor.getAllValues().get(0))
                .isNotEqualTo(keyCaptor.getAllValues().get(1));
    }

    @Test
    void cacheKey_hasCorrectPrefix() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);

        cacheService.get("tenant-1", "some question");

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).get(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("query:cache:");
    }
}
