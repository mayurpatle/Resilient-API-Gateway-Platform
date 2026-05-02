package com.mayur.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Defines how Spring Cloud Gateway identifies a "client" for rate limiting.
 *
 * Strategy: prefer authenticated user (X-User-Id, set by JWT filter);
 * fall back to remote IP for anonymous traffic on public endpoints.
 *
 * Why: IP-only rate limits are unfair behind NAT/proxies. Per-user
 * limits also defeat clients that try to evade limits by recycling tokens.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    @Primary
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(10, 20, 1);
    }

    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            // Fallback: client IP (for public endpoints).
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }
}
