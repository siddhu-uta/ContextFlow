package com.contextflow.gateway.config;

import java.util.Objects;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    /**
     * Primary resolver for authenticated routes.
     * Uses the X-Tenant-Id header injected by JwtGlobalFilter (order -100),
     * which always runs before rate limiting (order -1).
     * Falls back to "anonymous" for unauthenticated edge cases.
     */
    @Bean
    @Primary
    public KeyResolver tenantKeyResolver() {
        return exchange -> Mono.justOrEmpty(
                exchange.getRequest().getHeaders().getFirst("X-Tenant-Id")
        ).defaultIfEmpty("anonymous");
    }

    /**
     * IP-based resolver for public auth endpoints (register/login/refresh)
     * where no JWT exists yet, so tenant ID is unavailable.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
                       .getAddress().getHostAddress()
        );
    }
}
