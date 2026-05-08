package com.contextflow.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import javax.crypto.SecretKey;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.contextflow.gateway.config.GatewayProperties;
import com.contextflow.gateway.exception.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtGlobalFilter implements GlobalFilter, Ordered {

    private static final String BLOCKLIST_PREFIX = "jwt:blocklist:";

    // Paths that bypass JWT validation
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    );

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Strip any tenant headers the client might inject (security hardening)
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove("X-User-Id");
                    h.remove("X-Tenant-Id");
                    h.remove("X-User-Role");
                })
                .build();
        ServerWebExchange sanitized = exchange.mutate().request(stripped).build();

        if (isPublicPath(path)) {
            return chain.filter(sanitized);
        }

        String authHeader = stripped.getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return sendError(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = extractClaims(token);
            String jti = claims.getId();

            // Reactive Redis blocklist check — centralises what was a TODO in each service
            return redisTemplate.hasKey(BLOCKLIST_PREFIX + jti)
                    .flatMap(isBlocklisted -> {
                        if (Boolean.TRUE.equals(isBlocklisted)) {
                            log.warn("Blocked revoked token jti={}", jti);
                            return sendError(exchange, HttpStatus.UNAUTHORIZED, "Token has been revoked");
                        }

                        ServerHttpRequest enriched = stripped.mutate()
                                .header("X-User-Id",   claims.getSubject())
                                .header("X-Tenant-Id", claims.get("tenantId", String.class))
                                .header("X-User-Role", claims.get("role",     String.class))
                                .build();

                        return chain.filter(sanitized.mutate().request(enriched).build());
                    });

        } catch (JwtException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return sendError(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/actuator") ||
               PUBLIC_PATHS.stream().anyMatch(path::equals);
    }

    private Claims extractClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(
                properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Mono<Void> sendError(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(
                    ErrorResponse.of(status.value(), status.getReasonPhrase(), message));
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        // Run before rate limiting (-1) and routing (Integer.MIN_VALUE) filters
        return -100;
    }
}
