package com.contextflow.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.contextflow.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class JwtGlobalFilterTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-chars-long!!";

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    private JwtGlobalFilter filter;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties(
                SECRET, "http://auth-service", "http://ingestion-service", "http://query-service");
        filter = new JwtGlobalFilter(redisTemplate, props, new ObjectMapper());
    }

    // ── Public paths bypass filter ─────────────────────────────────────────

    @Test
    void publicPath_login_passesWithoutAuthHeader() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/v1/auth/login").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull(); // no error set
    }

    @Test
    void publicPath_register_passesWithoutAuthHeader() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/v1/auth/register").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.filter(exchange, chainOf(chainCalled)).block();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void actuatorPath_passesWithoutToken() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/actuator/health").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.filter(exchange, chainOf(chainCalled)).block();

        assertThat(chainCalled.get()).isTrue();
    }

    // ── Missing / malformed auth header ───────────────────────────────────

    @Test
    void protectedPath_noAuthHeader_returns401() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/query").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(exchange);
    }

    @Test
    void protectedPath_malformedBearerToken_returns401() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/v1/documents")
                        .header("Authorization", "Bearer not.a.jwt")
                        .build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedPath_wrongScheme_returns401() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/v1/documents")
                        .header("Authorization", "Basic dXNlcjpwYXNz")
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Valid JWT — not blocklisted ────────────────────────────────────────

    @Test
    void validToken_notBlocklisted_enrichesDownstreamHeaders() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = buildToken(jti, userId, tenantId, "ADMIN", 15);

        when(redisTemplate.hasKey("jwt:blocklist:" + jti)).thenReturn(Mono.just(false));

        AtomicReference<ServerHttpRequest> downstreamRequest = new AtomicReference<>();
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/v1/query")
                        .header("Authorization", "Bearer " + token)
                        .build());

        filter.filter(exchange, ex -> {
            downstreamRequest.set(ex.getRequest());
            return Mono.empty();
        }).block();

        assertThat(downstreamRequest.get()).isNotNull();
        assertThat(downstreamRequest.get().getHeaders().getFirst("X-User-Id"))
                .isEqualTo(userId.toString());
        assertThat(downstreamRequest.get().getHeaders().getFirst("X-Tenant-Id"))
                .isEqualTo(tenantId.toString());
        assertThat(downstreamRequest.get().getHeaders().getFirst("X-User-Role"))
                .isEqualTo("ADMIN");
    }

    // ── Blocklisted JWT ────────────────────────────────────────────────────

    @Test
    void validToken_blocklisted_returns401() {
        String jti = UUID.randomUUID().toString();
        String token = buildToken(jti, UUID.randomUUID(), UUID.randomUUID(), "ADMIN", 15);

        when(redisTemplate.hasKey("jwt:blocklist:" + jti)).thenReturn(Mono.just(true));

        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/v1/query")
                        .header("Authorization", "Bearer " + token)
                        .build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(exchange);
    }

    // ── Header injection protection ────────────────────────────────────────

    @Test
    void clientInjectedTenantHeader_isStripped_onUnauthenticatedRequest() {
        // A malicious client tries to spoof its tenant by injecting the header
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/v1/documents")
                        .header("X-Tenant-Id", "spoofed-tenant")
                        .header("X-User-Id", "spoofed-user")
                        .build());

        // No auth header — should still get 401, not pass with spoofed headers
        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void clientInjectedTenantHeader_isOverwritten_onValidRequest() {
        UUID realTenantId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = buildToken(jti, UUID.randomUUID(), realTenantId, "EDITOR", 15);

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        AtomicReference<ServerHttpRequest> downstreamRequest = new AtomicReference<>();
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/v1/documents")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", "attacker-tenant")  // injected by client
                        .build());

        filter.filter(exchange, ex -> {
            downstreamRequest.set(ex.getRequest());
            return Mono.empty();
        }).block();

        // The downstream request must have the real tenant from the JWT, not the injected one
        assertThat(downstreamRequest.get().getHeaders().getFirst("X-Tenant-Id"))
                .isEqualTo(realTenantId.toString());
    }

    // ── Filter ordering ────────────────────────────────────────────────────

    @Test
    void filterOrder_isMinusOneHundred() {
        assertThat(filter.getOrder()).isEqualTo(-100);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildToken(String jti, UUID userId, UUID tenantId, String role, int expiryMinutes) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .id(jti)
                .subject(userId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("role", role)
                .expiration(Date.from(Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }

    private GatewayFilterChain chainOf(AtomicBoolean called) {
        return ex -> {
            called.set(true);
            return Mono.empty();
        };
    }
}
