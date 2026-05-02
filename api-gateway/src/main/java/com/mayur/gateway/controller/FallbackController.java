package com.mayur.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Returns graceful fallback responses when a circuit breaker trips.
 *
 * Each downstream route maps to a dedicated fallback so the response can
 * be tailored — e.g., serve a cached last-known response, an empty list,
 * or a friendly "try again later" depending on the endpoint's semantics.
 */
@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/users")
    public Mono<ResponseEntity<Map<String, Object>>> userServiceFallback() {
        log.warn("User service circuit breaker triggered fallback");
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "service", "user-service",
                        "status", "DEGRADED",
                        "message", "User service is temporarily unavailable. Please retry shortly.",
                        "timestamp", Instant.now().toString()
                )));
    }

    @GetMapping("/orders")
    @PostMapping("/orders")
    public Mono<ResponseEntity<Map<String, Object>>> orderServiceFallback() {
        log.warn("Order service circuit breaker triggered fallback");
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "service", "order-service",
                        "status", "DEGRADED",
                        "message", "Order service is temporarily unavailable. Your request was not processed.",
                        "retryAfterSeconds", 10,
                        "timestamp", Instant.now().toString()
                )));
    }
}
