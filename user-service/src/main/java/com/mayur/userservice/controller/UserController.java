package com.mayur.userservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User endpoints + a /chaos toggle so we can deliberately make the
 * service fail or stall, to demonstrate the circuit breaker tripping.
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AtomicBoolean failureMode = new AtomicBoolean(false);
    private final AtomicBoolean slowMode = new AtomicBoolean(false);

    @GetMapping
    public List<Map<String, Object>> listUsers(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles
    ) throws InterruptedException {
        log.info("listUsers called by userId={}, roles={}", userId, roles);

        if (failureMode.get()) {
            throw new RuntimeException("Simulated user-service failure (chaos mode ON)");
        }
        if (slowMode.get()) {
            // Longer than the gateway's 3s time limiter — circuit opens.
            Thread.sleep(5000);
        }

        return List.of(
                Map.of("id", 1, "name", "Mayur Patle", "role", "Backend Engineer"),
                Map.of("id", 2, "name", "Demo User", "role", "QA")
        );
    }

    @GetMapping("/{id}")
    public Map<String, Object> getUser(@PathVariable Long id) {
        if (failureMode.get()) {
            throw new RuntimeException("Simulated user-service failure");
        }
        return Map.of("id", id, "name", "User " + id, "email", "user" + id + "@example.com");
    }

    // ---------- chaos toggles (demo only) ----------

    @PostMapping("/chaos/fail")
    public Map<String, Object> toggleFailure(@RequestParam boolean enabled) {
        failureMode.set(enabled);
        log.warn("Failure mode toggled: {}", enabled);
        return Map.of("failureMode", enabled);
    }

    @PostMapping("/chaos/slow")
    public Map<String, Object> toggleSlow(@RequestParam boolean enabled) {
        slowMode.set(enabled);
        log.warn("Slow mode toggled: {}", enabled);
        return Map.of("slowMode", enabled);
    }
}
