package com.mayur.orderservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final AtomicBoolean failureMode = new AtomicBoolean(false);

    @GetMapping
    public List<Map<String, Object>> listOrders(
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        log.info("listOrders called by userId={}", userId);
        if (failureMode.get()) {
            throw new RuntimeException("Simulated order-service failure");
        }
        return List.of(
                Map.of("orderId", "ORD-1001", "userId", userId, "amount", 1299.00, "status", "PAID"),
                Map.of("orderId", "ORD-1002", "userId", userId, "amount", 549.50, "status", "SHIPPED")
        );
    }

    @PostMapping
    public Map<String, Object> createOrder(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, Object> request
    ) {
        if (failureMode.get()) {
            throw new RuntimeException("Simulated order-service failure");
        }
        return Map.of(
                "orderId", "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "userId", userId,
                "amount", request.getOrDefault("amount", 0),
                "status", "CREATED",
                "createdAt", Instant.now().toString()
        );
    }

    @PostMapping("/chaos/fail")
    public Map<String, Object> toggleFailure(@RequestParam boolean enabled) {
        failureMode.set(enabled);
        return Map.of("failureMode", enabled);
    }
}
