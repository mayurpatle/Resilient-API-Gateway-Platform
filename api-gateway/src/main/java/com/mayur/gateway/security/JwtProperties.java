package com.mayur.gateway.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Externalized configuration for JWT validation.
 * In production, the secret should come from a secrets manager
 * (AWS Secrets Manager, HashiCorp Vault), never application.yml.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.security.jwt")
public class JwtProperties {

    /** HMAC-SHA256 signing secret (>= 256 bits). */
    private String secret;

    /** Endpoints that bypass JWT validation (login, health, docs). */
    private List<String> publicPaths = List.of(
            "/auth/login",
            "/auth/register",
            "/actuator/health",
            "/fallback/**"
    );
}
