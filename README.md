# Resilient API Gateway Platform

> Production-grade Spring Cloud Gateway implementing JWT authentication, distributed rate limiting (Redis token bucket), and Resilience4j circuit breakers — with a working chaos-engineering harness to prove every layer.

[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.1-blue)](https://spring.io/projects/spring-cloud)
[![Redis](https://img.shields.io/badge/Redis-7-red)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue)](https://docs.docker.com/compose/)

---

## Why This Project Exists

Most "Spring Cloud Gateway tutorials" stop at routing. Real systems need three more things:

1. **Authentication** — reject anonymous traffic before it consumes resources
2. **Rate limiting** — protect downstream services from a single misbehaving client
3. **Circuit breaking** — stop cascading failures when a downstream service is slow or dead

This project implements all three as a **single layer of middleware**, demonstrates each one tripping under controlled chaos, and ships with a Postman collection so anyone can verify the behavior in 5 minutes.

---

## Architecture

```
┌─────────┐
│ Client  │
└────┬────┘
     │ Authorization: Bearer <jwt>
     ▼
┌──────────────────────────────────────────────────────────────────┐
│                  API Gateway (port 8080)                         │
│                                                                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │ 1. JwtAuthFilter │→ │ 2. RateLimiter   │→ │ 3. CircuitBkr  │  │
│  │  HIGHEST_PREC    │  │  Redis tokens    │  │  Resilience4j  │  │
│  │  Reject anon     │  │  Per-user bucket │  │  Per-route CB  │  │
│  └──────────────────┘  └──────────────────┘  └────────────────┘  │
│           │                     │                    │           │
│           │                     ▼                    │           │
│           │              ┌─────────────┐             │           │
│           │              │   Redis     │             │           │
│           │              │  :6379      │             │           │
│           │              └─────────────┘             │           │
└───────────┼─────────────────────────────────────────┼────────────┘
            │                                         │
            ▼                                         ▼
   ┌──────────────────┐                   ┌──────────────────┐
   │  user-service    │                   │  order-service   │
   │     :8081        │                   │     :8082        │
   │  (chaos toggles) │                   │  (chaos toggles) │
   └──────────────────┘                   └──────────────────┘
```

**Filter order matters.** JWT runs first (cheapest reject), then rate-limiting (still cheap), then circuit-breaking (which actually makes the downstream call). Reversing the order would waste rate-limit capacity on garbage tokens.

---

## What Each Layer Does

### 1. JWT Authentication — `JwtAuthenticationFilter.java`

A `GlobalFilter` running at `HIGHEST_PRECEDENCE`. Verifies the HMAC-signed token, extracts claims, and **propagates identity to downstream services as headers** (`X-User-Id`, `X-User-Roles`):

```java
ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
    .header("X-User-Id", claims.getSubject())
    .header("X-User-Roles", String.valueOf(claims.get("roles", String.class)))
    .build();
return chain.filter(exchange.mutate().request(mutatedRequest).build());
```

**Why this matters:** downstream services don't re-parse the token. They trust the headers because only the gateway can reach them (network policy / service mesh in production). One verification at the edge instead of N verifications across N services.

### 2. Distributed Rate Limiting — Redis Token Bucket

Per-user token bucket backed by Redis. The `KeyResolver` reads `X-User-Id` (set by the JWT filter) so limits are **per authenticated user**, not per IP — fair behind NAT, hard to evade by recycling tokens.

```yaml
- name: RequestRateLimiter
  args:
    redis-rate-limiter.replenishRate: 10    # 10 req/sec sustained
    redis-rate-limiter.burstCapacity: 20    # allow burst up to 20
    redis-rate-limiter.requestedTokens: 1
    key-resolver: "#{@userKeyResolver}"
```

Different routes get different profiles. The order endpoint is stricter (`5/sec, burst 10`) because writes are more expensive and orders are the critical path.

### 3. Circuit Breaker — Resilience4j

Per-route circuit breakers with custom thresholds. The default config:

```java
CircuitBreakerConfig.custom()
    .slidingWindowSize(20)
    .minimumNumberOfCalls(10)
    .failureRateThreshold(50.0f)
    .slowCallRateThreshold(70.0f)
    .slowCallDurationThreshold(Duration.ofSeconds(2))
    .waitDurationInOpenState(Duration.ofSeconds(10))
    .permittedNumberOfCallsInHalfOpenState(5)
    .automaticTransitionFromOpenToHalfOpenEnabled(true)
    .build()
```

**Slow calls count as failures.** A 5-second response is almost as bad as a 500. The 3-second `TimeLimiter` kills hung calls before they exhaust Reactor threads — this is the actual mechanism that prevents cascading failure.

When the breaker opens, requests are forwarded to a `FallbackController` that returns a graceful 503 with a `retryAfterSeconds` hint instead of timing out.

---

## Quick Start

**Prerequisites:** Docker + Docker Compose. That's it.

```bash
git clone <this-repo>
cd resilient-gateway-platform
docker compose up --build
```

Wait for `Started ApiGatewayApplication`. Then in a new terminal:

```bash
# 1. Get a token
curl -X POST http://localhost:8080/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"mayur"}'
# Copy the accessToken from the response

# 2. Call a protected route
TOKEN="<paste token>"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/users
```

You should get a list of users. Done.

---

## Demo Scenarios (Proving It Actually Works)

### Demo 1 — Reject Anonymous Traffic

```bash
curl -i http://localhost:8080/api/users
```

```
HTTP/1.1 401 Unauthorized
X-Auth-Error: Missing or invalid Authorization header
```

The JWT filter rejected the request before it reached `user-service`. Verify by tailing user-service logs — no entry for that call.

### Demo 2 — Trigger the Rate Limiter

PowerShell (works on PS 5.1+):

```powershell
$tokenResponse = Invoke-RestMethod -Uri "http://localhost:8080/auth/login" `
    -Method Post -ContentType "application/json" -Body '{"username":"mayur"}'
$token = $tokenResponse.accessToken

$counts = @{ '200' = 0; '429' = 0 }
1..50 | ForEach-Object {
    try {
        Invoke-WebRequest -Uri "http://localhost:8080/api/users" `
            -Headers @{ "Authorization" = "Bearer $token" } `
            -UseBasicParsing -ErrorAction Stop | Out-Null
        $counts['200']++
        Write-Host -NoNewline "." -ForegroundColor Green
    } catch {
        if ([int]$_.Exception.Response.StatusCode -eq 429) {
            $counts['429']++
            Write-Host -NoNewline "X" -ForegroundColor Red
        }
    }
}
Write-Host "`n✅ 200 OK:        $($counts['200'])"
Write-Host "🚫 429 Limited:    $($counts['429'])"
```

Output:
```
..............................XXXXXXXXXXXXXXXXXXXX
✅ 200 OK:        30
🚫 429 Limited:    20
```

The bucket starts at 20 (burstCapacity), refills at 10/sec, and PowerShell fires faster than refill — so 30 succeed (20 burst + ~10 refilled during the run) and 20 hit empty bucket.

### Demo 3 — Inspect the Bucket Live

```bash
docker exec gateway-redis redis-cli MGET \
  "request_rate_limiter.{user:mayur}.tokens" \
  "request_rate_limiter.{user:mayur}.timestamp"
```

```
1) "9"
2) "1730548720"
```

9 tokens left (you used 1 of 10 from the orders bucket). The atomic Lua script inside Redis handles refill+decrement in a single round trip.

### Demo 4 — Trip the Circuit Breaker

```bash
# Make user-service start failing
curl -X POST "http://localhost:8081/api/users/chaos/fail?enabled=true"

# Hammer the gateway
TOKEN="<your token>"
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
       -H "Authorization: Bearer $TOKEN" \
       http://localhost:8080/api/users
done
```

You'll see:
```
500
500
500
...   (10 real failures — breaker is counting)
500
503   ← breaker just OPENED
503   ← these return INSTANTLY from the fallback
503
...
```

The transition from `500` to `503` is the breaker tripping. After 10 failures (`minimumNumberOfCalls`) at >50% rate, it flipped to `OPEN` and started serving the fallback — **without making downstream calls**. That's how cascading failure is prevented.

```bash
# Inspect breaker state
curl http://localhost:8080/actuator/circuitbreakers | jq
```

```json
{
  "userServiceCircuitBreaker": {
    "state": "OPEN",
    "failureRate": "100.0%",
    "failedCalls": 10,
    "notPermittedCalls": 27,    // requests rejected without trying
    "successfulCalls": 0
  }
}
```

### Demo 5 — Self-Recovery

```bash
# Stop the chaos
curl -X POST "http://localhost:8081/api/users/chaos/fail?enabled=false"

# Wait for waitDurationInOpenState (10s)
sleep 11

# Send 5 successful probes
for i in {1..5}; do
  curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/users
done

# Check state — back to CLOSED
curl http://localhost:8080/actuator/circuitbreakers | jq '.circuitBreakers.userServiceCircuitBreaker.state'
# "CLOSED"
```

The breaker auto-transitioned `OPEN → HALF_OPEN → CLOSED` based on the 5 probe successes. **No manual intervention needed** — that's the design.

---

## A Bug I Hit and Fixed

**Symptom:** rate limiter filter was registered on every route (visible in `/actuator/gateway/routes`), Redis was healthy, no errors in logs — but 429s never fired and Redis stayed empty after every request.

**Root cause:** Spring Cloud Gateway 2023.0.x's `RedisRateLimiter` auto-configuration silently failed to register a bean in the containerized environment. The filter ran but had no rate limiter to delegate to, so it became a no-op (Spring Cloud Gateway is designed to **fail open** on rate-limit-backend issues — better availability over silent breakage).

**Fix:** explicitly declare the bean in `RateLimiterConfig.java`:

```java
@Bean
@Primary
public RedisRateLimiter redisRateLimiter() {
    return new RedisRateLimiter(10, 20, 1);
}
```

**Diagnostic that exposed it:**

```bash
docker exec gateway-redis redis-cli KEYS "*"
# (empty array)   ← should have shown request_rate_limiter.* keys
```

Empty Redis after a successful gateway call = the filter wasn't actually engaging. Auto-config relies on bean conditions; explicit beans bypass those. Lesson: when Spring's "magic" silently no-ops, the diagnostic is to check the **actual side effect** (Redis state), not just configuration files or actuator endpoints.

---

## Project Structure

```
resilient-gateway-platform/
├── api-gateway/
│   ├── src/main/java/com/mayur/gateway/
│   │   ├── ApiGatewayApplication.java
│   │   ├── config/
│   │   │   ├── CircuitBreakerConfiguration.java   # Resilience4j tuning
│   │   │   └── RateLimiterConfig.java             # KeyResolver + RedisRateLimiter beans
│   │   ├── controller/
│   │   │   ├── AuthController.java                # /auth/login (demo JWT issuer)
│   │   │   └── FallbackController.java            # graceful 503 responses
│   │   ├── filter/
│   │   │   └── JwtAuthenticationFilter.java       # global JWT validation
│   │   └── security/
│   │       ├── JwtProperties.java
│   │       └── JwtTokenValidator.java             # HMAC-SHA verification
│   └── src/main/resources/
│       └── application.yml                        # routes, filters, limits
├── user-service/        # downstream + /chaos/fail, /chaos/slow toggles
├── order-service/       # downstream + /chaos/fail toggle
├── docker-compose.yml
├── postman_collection.json   # full test suite (auth, rate limit, CB demos)
└── pom.xml              # multi-module parent
```

---

## Configuration Reference

### Per-Route Rate Limits

| Route | replenishRate | burstCapacity | Rationale |
|---|---|---|---|
| `/api/users/**` | 10 req/s | 20 | Read-heavy, generous |
| `/api/orders/**` | 5 req/s | 10 | Write-heavy, expensive (DB tx, payment) |

### Per-Route Circuit Breakers

| Breaker | failureRate | slidingWindow | timeout | Rationale |
|---|---|---|---|---|
| `userServiceCircuitBreaker` | 50% | 20 calls | 3s | Default profile |
| `orderServiceCircuitBreaker` | 40% | 15 calls | 2s | Critical path — fail faster |

Configured in `application.yml` (rate limits) and `CircuitBreakerConfiguration.java` (programmatic CB tuning).

---

## Observability

| Endpoint | What |
|---|---|
| `GET /actuator/health` | Overall + Redis + circuit breaker states |
| `GET /actuator/circuitbreakers` | Live state of each CB (CLOSED/OPEN/HALF_OPEN) |
| `GET /actuator/circuitbreakerevents` | Per-call event log — invaluable for debugging |
| `GET /actuator/gateway/routes` | Registered routes + filters (verify YAML is loaded) |
| `GET /actuator/metrics/resilience4j.circuitbreaker.calls` | Counts: success / fail / slow / not-permitted |

---

## What's Not Done (Production Hardening Backlog)

This is a working demo, not a hardened production system. Real deployment would need:

- [ ] **Asymmetric JWTs (RS256)** so only the auth service holds the private key; the gateway only needs the public key
- [ ] **JWT secret from a secrets manager** (AWS Secrets Manager, Vault) — not application.yml
- [ ] **mTLS** between gateway and downstream services (defense in depth)
- [ ] **Service discovery** (Eureka, Consul, or K8s DNS) instead of hardcoded URIs
- [ ] **Distributed tracing** — correlation ID filter + OpenTelemetry → Jaeger/Tempo
- [ ] **Prometheus + Grafana** for live metrics dashboards
- [ ] **Tiered rate limits** — Free vs Pro plans with different quotas
- [ ] **Real load tests** (k6 or JMeter) baked into CI

These are the obvious next steps for a 1.0 → 2.0 evolution.

---

## Tech Stack

- **Java 17** + **Spring Boot 3.2.5**
- **Spring Cloud Gateway 2023.0.1** (reactive, WebFlux-based)
- **Resilience4j** (circuit breaker + time limiter)
- **Redis 7** (rate-limit state, atomic Lua scripts)
- **JJWT 0.12** (token signing/verification)
- **Docker Compose** (orchestration)
- **Maven multi-module** (parent POM + 3 children)

---

## License

MIT — use it, fork it, learn from it.

---

## Author

**Mayur Patle** — Backend Engineer | [LinkedIn](#) | [GitHub](#)

If this helped you understand gateway patterns, ⭐ the repo. If you spot a bug or improvement, PRs welcome.