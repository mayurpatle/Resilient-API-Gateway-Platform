package com.mayur.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Cloud Gateway entry point.
 *
 * Provides centralized:
 *  - JWT authentication (global filter)
 *  - Distributed rate limiting (Redis + token bucket)
 *  - Circuit breaking (Resilience4j) per downstream route
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
