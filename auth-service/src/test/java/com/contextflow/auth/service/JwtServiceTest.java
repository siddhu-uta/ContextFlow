package com.contextflow.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.contextflow.auth.config.JwtProperties;
import com.contextflow.auth.entity.Tenant;
import com.contextflow.auth.entity.User;
import com.contextflow.auth.entity.UserRole;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-chars-long!!";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(SECRET, 15, 7, "contextflow");
        jwtService = new JwtService(props, redisTemplate);
    }

    // ── Token generation ─────────────────────────────────────────────────────

    @Test
    void generateAccessToken_containsCorrectClaims() {
        User user = buildUser("alice@test.com", UserRole.ADMIN);

        String token = jwtService.generateAccessToken(user);
        Claims claims = jwtService.extractClaims(token);

        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get("tenantId", String.class)).isEqualTo(user.getTenant().getId().toString());
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.get("email", String.class)).isEqualTo("alice@test.com");
        assertThat(claims.getIssuer()).isEqualTo("contextflow");
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    void generateAccessToken_expiryIsInFuture() {
        User user = buildUser("bob@test.com", UserRole.VIEWER);
        String token = jwtService.generateAccessToken(user);

        Claims claims = jwtService.extractClaims(token);
        assertThat(claims.getExpiration().toInstant()).isAfter(Instant.now());
    }

    @Test
    void generateAccessToken_eachCallProducesDifferentJti() {
        User user = buildUser("carol@test.com", UserRole.EDITOR);

        String token1 = jwtService.generateAccessToken(user);
        String token2 = jwtService.generateAccessToken(user);

        String jti1 = jwtService.extractClaims(token1).getId();
        String jti2 = jwtService.extractClaims(token2).getId();
        assertThat(jti1).isNotEqualTo(jti2);
    }

    // ── Claims extraction ─────────────────────────────────────────────────────

    @Test
    void extractClaims_tamperedSignature_throwsJwtException() {
        User user = buildUser("dan@test.com", UserRole.ADMIN);
        String token = jwtService.generateAccessToken(user);
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "invalidsig";

        assertThatThrownBy(() -> jwtService.extractClaims(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void extractClaims_wrongSecret_throwsJwtException() {
        // Token signed with a different secret
        JwtProperties otherProps = new JwtProperties("other-secret-key-32-chars-padding!!", 15, 7, "x");
        JwtService otherService = new JwtService(otherProps, redisTemplate);
        User user = buildUser("eve@test.com", UserRole.ADMIN);
        String foreignToken = otherService.generateAccessToken(user);

        assertThatThrownBy(() -> jwtService.extractClaims(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    // ── Refresh token hashing ────────────────────────────────────────────────

    @Test
    void hashRefreshToken_sameInputAlwaysReturnsSameHash() {
        String raw = "some-raw-refresh-token-value";
        assertThat(jwtService.hashRefreshToken(raw))
                .isEqualTo(jwtService.hashRefreshToken(raw));
    }

    @Test
    void hashRefreshToken_producesHexEncodedSha256() {
        String hash = jwtService.hashRefreshToken("anything");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void hashRefreshToken_differentInputsProduceDifferentHashes() {
        assertThat(jwtService.hashRefreshToken("token-a"))
                .isNotEqualTo(jwtService.hashRefreshToken("token-b"));
    }

    // ── Redis blocklist ──────────────────────────────────────────────────────

    @Test
    void isTokenBlocklisted_keyAbsent_returnsFalse() {
        when(redisTemplate.hasKey("jwt:blocklist:some-jti")).thenReturn(false);
        assertThat(jwtService.isTokenBlocklisted("some-jti")).isFalse();
    }

    @Test
    void isTokenBlocklisted_keyPresent_returnsTrue() {
        when(redisTemplate.hasKey("jwt:blocklist:revoked-jti")).thenReturn(true);
        assertThat(jwtService.isTokenBlocklisted("revoked-jti")).isTrue();
    }

    @Test
    void blocklistToken_positiveTtl_storesKeyInRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        Instant expiry = Instant.now().plus(10, ChronoUnit.MINUTES);

        jwtService.blocklistToken("jti-123", expiry);

        verify(valueOps).set(eq("jwt:blocklist:jti-123"), eq("1"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void blocklistToken_alreadyExpiredToken_doesNotWriteToRedis() {
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.MINUTES);

        jwtService.blocklistToken("expired-jti", pastExpiry);

        verify(redisTemplate, never()).opsForValue();
    }

    // ── isTokenValid composite ────────────────────────────────────────────────

    @Test
    void isTokenValid_validNonBlocklistedToken_returnsTrue() {
        User user = buildUser("frank@test.com", UserRole.ADMIN);
        String token = jwtService.generateAccessToken(user);
        String jti = jwtService.extractClaims(token).getId();

        when(redisTemplate.hasKey("jwt:blocklist:" + jti)).thenReturn(false);

        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void isTokenValid_blocklistedToken_returnsFalse() {
        User user = buildUser("grace@test.com", UserRole.ADMIN);
        String token = jwtService.generateAccessToken(user);
        String jti = jwtService.extractClaims(token).getId();

        when(redisTemplate.hasKey("jwt:blocklist:" + jti)).thenReturn(true);

        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User buildUser(String email, UserRole role) {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Test Corp");
        tenant.setSlug("test-corp");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setTenant(tenant);
        user.setRole(role);
        return user;
    }
}
