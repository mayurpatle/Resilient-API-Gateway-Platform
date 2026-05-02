# Resilient API Gateway Platform

A production-grade Spring Cloud Gateway demonstrating three layers of defense for a microservices platform:

1. **JWT authentication** as a global filter (early reject, identity propagation via headers)
2. **Distributed rate limiting** (Redis-backed token bucket, per-authenticated-user keys)
3. **Resilience4j circuit breakers** with route-specific tuning and graceful fallbacks

The combination prevents cascading failures during peak-load: unauthenticated traffic is rejected before it consumes downstream resources, abusive clients are throttled per-user, and slow/failing downstreams are isolated within ~10 seconds before they exhaust the gateway's connection pool.

## Architecture

```
Client → [API Gateway :8080]
            ├─ JwtAuthenticationFilter   (HIGHEST_PRECEDENCE)
            ├─ RequestRateLimiter        (Redis token bucket, per X-User-Id)
            └─ CircuitBreaker            (Resilience4j, fallback URI per route)
                  ├─ /api/users/**  → user-service  :8081
                  └─ /api/orders/** → order-service :8082

         [Redis :6379]  (rate-limit state)
```

## Module layout

```
resilient-gateway-platform/
├── api-gateway/         # Spring Cloud Gateway + JWT + rate limit + CB
├── user-service/        # Demo downstream + chaos toggles
├── order-service/       # Demo downstream + chaos toggles
├── docker-compose.yml
└── pom.xml
```

## Running locally

### Option A — Docker Compose (recommended)

```bash
docker compose up --build
```

### Option B — Maven (separate terminals)

```bash
# Start Redis first
docker run -d -p 6379:6379 redis:7-alpine

# Each in its own terminal
mvn -pl user-service spring-boot:run
mvn -pl order-service spring-boot:run
mvn -pl api-gateway spring-boot:run
```

## Trying it out

### 1. Get a JWT

```bash
curl -X POST http://localhost:8080/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"mayur"}'
```

Save the `accessToken` from the response.

### 2. Call a protected route

```bash
TOKEN="<paste token>"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/users
```

### 3. Trigger the rate limiter

```bash
for i in {1..40}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
       -H "Authorization: Bearer $TOKEN" \
       http://localhost:8080/api/users
done
```

You'll see `200`s, then `429 Too Many Requests` once the burst is exhausted.

### 4. Trip the circuit breaker

```bash
# Make user-service start failing
curl -X POST "http://localhost:8081/api/users/chaos/fail?enabled=true"

# Hammer the gateway — first ~10 calls fail, then the breaker opens and
# requests are served instantly from /fallback/users
for i in {1..30}; do
  curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/users
  echo
done

# Watch the breaker state
curl http://localhost:8080/actuator/circuitbreakers | jq

# Recover
curl -X POST "http://localhost:8081/api/users/chaos/fail?enabled=false"
```

### 5. Trip via slow responses (time limiter)

```bash
curl -X POST "http://localhost:8081/api/users/chaos/slow?enabled=true"
# Calls now take 5s. Gateway's 3s timeLimiter fires → counted as failures →
# breaker opens.
```

## Key design notes

| Concern | Decision | Why |
|---|---|---|
| Filter order | JWT runs at `HIGHEST_PRECEDENCE` | Reject anonymous traffic before it consumes rate-limit / CB capacity |
| Identity propagation | Inject `X-User-Id`, `X-User-Roles` headers | Downstream services don't re-parse the token; they trust headers because only the gateway reaches them |
| Rate-limit key | Per-authenticated-user, IP fallback | IP-only is unfair behind NAT; per-user defeats token recycling |
| Token bucket | replenishRate=10/s, burst=20 (users) and 5/s, burst=10 (orders) | Reads cheap, writes expensive — protect order service harder |
| CB sliding window | 20 calls, 50% threshold, 10 calls min | Doesn't trip on a single blip; reacts within seconds at real volume |
| Time limiter | 3s gateway-wide, 2s for orders | Fail fast — better a fallback than a stuck Reactor thread |
| Recovery | `automaticTransitionFromOpenToHalfOpen=true`, 5 probe calls | Self-healing without manual intervention |

## Observability

```bash
curl http://localhost:8080/actuator/health           # overall + CB state
curl http://localhost:8080/actuator/circuitbreakers  # per-CB metrics
curl http://localhost:8080/actuator/circuitbreakerevents
curl http://localhost:8080/actuator/gateway/routes   # registered routes
```

## Production hardening checklist

- [ ] Replace JWT secret with a value from AWS Secrets Manager / Vault
- [ ] Switch to RS256 (asymmetric) so only the auth service can mint tokens
- [ ] Add request correlation ID filter for distributed tracing
- [ ] Wire up Micrometer → Prometheus → Grafana dashboards for CB state
- [ ] Add an admin-only `/admin/**` route group with stricter rate limits
- [ ] mTLS between gateway and downstream services
- [ ] Replace HTTP downstream URIs with service discovery (Eureka, Consul, K8s DNS)
