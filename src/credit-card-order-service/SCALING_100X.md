# Scaling to 100x Peak Traffic — Findings & Actions

Based on Bluebox modeling of the EasyTrade production tenant (2026-09-29).
*Service scoping was name-matched in Bluebox and flagged low-confidence; the
numbers are directional. Host/process CPU and DB connection-pool limits could
not be independently confirmed — see "Observability gaps" below.*

## Projected load at 100x current peak

| Component | Current peak | 100x projected |
|---|---|---|
| credit-card-order-service (all endpoints) | ~0.22 req/s | ~22 req/s |
| feature-flag-service `GET /v1/flags/{id}` | ~2.25 req/s | ~225 req/s |
| third-party-service `POST /v1/manufacturer` | ~0.003 req/s (bursty) | ~0.33 req/s sustained, bursty |
| credit-card-order-service → DB | ~0.32 calls/s | ~32 calls/s |

Slowest hop: third-party-service manufacturer call — avg 3.1s, **p95 4.3s, p99 5.5s**
(2–3 orders of magnitude slower than feature-flag ~0.8ms, DB ~1.7ms, status ~30ms).

## Projected failure order (what breaks first)

1. **credit-card-order-service thread / connection-pool exhaustion.** Triggered by
   the synchronous multi-second third-party-service call. Observed runtime headroom
   today is tiny (~10–12 thread-pool threads, ~11 Kestrel connections, ~8 outbound
   HTTP connections). Each blocked call holds a slot for seconds → the pool drains.
2. **Cross-endpoint cascade.** Once the pool is starved, the unrelated high-volume
   `status/latest` polling endpoint — which shares the same pool — starts queuing
   and timing out even though it has no third-party dependency.
3. **feature-flag-service at ~225 req/s** — likely survives on latency alone, but
   unverified; a downstream risk only if it can't scale horizontally.
4. **Database at ~32 calls/s from this flow** — lowest risk, but shared with
   contentcreator, so app-wide 100x load could compound connection pressure.

---

## Addressed in code (this PR)

These directly attack failure points #1 and #2:

- **Timeouts on the third-party-service call** (`WorkScheduler`): 3s connect, 6s
  request (just above observed p99). A hung/slow provider now fails fast instead
  of holding a worker thread + outbound connection open indefinitely.
- **Per-order isolation in `WorkScheduler`**: one timeout/error no longer aborts
  the whole batch. Failed orders stay `ORDER_CREATED` and retry next pass.
- **Timeouts + fail-open on the feature-flag call** (`FeatureFlagClient`): 1s
  connect, 2s request; on failure it defaults the flag to *disabled* rather than
  throwing. A feature-flag-service blip can no longer cascade into the status
  endpoint (which reads a flag on every request).
- **Async bitcoin settlement** (existing in this PR): the new payment path never
  does the slow provider call inline — it returns `202` and settles in the
  background `BitcoinPaymentScheduler`, which is also timeout-bounded.

---

## Requires INFRASTRUCTURE / CONFIG changes (not code — flagged for the team)

> These cannot be fixed in application logic alone. Owner: platform/SRE.

1. **Horizontal scaling of credit-card-order-service.** The service is stateless
   (all state in MSSQL), so raise helm `replicaCount` and CPU/memory limits (today:
   requests 20m CPU / 650Mi, limits 500m / 650Mi). Put it behind the existing
   reverse proxy with autoscaling (HPA on CPU + request rate).
   - **Caveat:** the background schedulers (`WorkScheduler`, `BitcoinPaymentScheduler`)
     must NOT run in every replica or they'll double-process. Either run a single
     dedicated worker deployment, or add DB row-locking to the pending-order queries
     (`SELECT ... WITH (UPDLOCK, READPAST)`). **This is a required follow-up before
     scaling replicas.**

2. **Thread-pool / connection sizing.** Current runtime is sized for ~10 concurrent
   slots. Tune the web server worker threads and the JDK `HttpClient` executor for
   the target concurrency, and load-test to find the real ceiling.

3. **DB connection pooling (HikariCP).** `DatabaseHelper` opens a raw
   `DriverManager` connection per call — fine at ~0.3/s, a churn/exhaustion risk at
   ~32/s (and worse app-wide). Introduce a pooled `DataSource`. This is partly a
   code change but needs coordinated DB `max_connections` sizing.

4. **Circuit breaker / bulkhead around external providers.** No resilience library
   exists in the repo. Add Resilience4j around the third-party-service and
   bitcoin-payment-service calls so a provider outage sheds load instead of piling
   up. (Timeouts added here are the minimum; a breaker is the next step.)

5. **Cache the hot read.** At 100x, `status/latest` polling dominates. Add a
   short-TTL cache (edge/CDN or in-service) and client-side flag caching to keep
   feature-flag-service flat at ~225 req/s.

6. **Queue-based handoff beyond ~10 rps.** The scheduler DB-poll pattern is fine to
   ~10 rps; past that, move pending-payment handoff onto the existing RabbitMQ so
   settlement workers scale independently.

7. **Database capacity for shared load.** The DB also serves contentcreator; size
   connection limits / instance for app-wide 100x, not just the payment flow.

## Observability gaps to close (from Bluebox)

- Per-service host/process **CPU & memory** don't resolve to Smartscape entities —
  can't confirm CPU saturation order. Wire up host/process metrics for
  credit-card-order-service, feature-flag-service, third-party-service.
- No telemetry on **retry policies / HTTP client timeouts** today — validate the
  new timeouts under load.
- **third-party-service datastore is untraced** — a capacity black box; instrument
  it before relying on it (or any external provider) at higher volume.
