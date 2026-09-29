# Bitcoin Payment Feature — Design & Capacity Analysis

> Grounded in production telemetry from Bluebox (EasyTrade tenant), gathered
> 2026-09-29. Service-scoping in Bluebox was name-matched (flagged low-confidence
> by Bluebox); the numbers below are directional baselines for capacity planning.

## Feature summary

Adds a **bitcoin payment** option to the credit-card order flow. A client can
initiate a bitcoin payment for an existing order; the service records the intent,
then settles it **asynchronously** against an external payment provider
(`bitcoin-payment-service`, reached over the same `HttpClient` convention used
for `third-party-service`). Settlement status is written back to the order's
status history, and clients poll the existing status endpoints.

The whole feature is gated behind a new OpenFeature flag, `bitcoin_payment`
(default **off**), so it can be dark-launched and rolled out gradually.

---

## 1. Components under the most stress

Current 24h payment-path baselines from Bluebox:

| Component | Volume (24h) | Latency (p95 / p99) | Assessment |
|---|---|---|---|
| **third-party-service** `POST /v1/manufacturer` | 21 calls | **~4.31 s / ~5.52 s** | **Bottleneck.** Slowest hop by ~2 orders of magnitude. Any external-provider call (incl. bitcoin settlement) inherits this risk profile. |
| feature-flag-service `GET /v1/flags/{id}` | ~184K calls (~2.1 rps) | ~1.8 ms / ~3.5 ms | Very high volume, negligible latency. Read on **every** status check — a new flag adds to this. |
| credit-card-order-service `GET .../status/latest` | ~9,074 calls | ~40.6 ms / ~58 ms | Hottest CCOS endpoint; each call does 1 flag lookup + 1 DB read. |
| credit-card-order-service `POST .../{id}/status` (callback) | ~78–100 calls | ~120 ms / ~159 ms | Write path; 3 sequential DB writes per call. |
| ostroski-easytrade-db | ~8,395 calls (~0.1 cps) | ~3.9 ms / ~7.1 ms | Fast, not strained today. |

**What bitcoin payment stresses most:**

1. **The external provider call (highest risk).** Modeled on `third-party-service`,
   a synchronous settlement call would sit at multi-second latency. Doing it
   inline on a request thread is the single biggest scaling hazard — it ties up
   threads and cascades latency to callers. **This must be asynchronous with a
   timeout + bounded retry.**
2. **The database** becomes the next constraint at high volume: each payment adds
   an intent row + N status rows. At 100x, DB write rate on the payment path grows
   from ~0.1 cps toward ~10 cps — still modest for MSSQL, but the polling read
   pattern (`status/latest`) is what actually dominates and needs caching.
3. **feature-flag-service** is read on every status poll. It has huge headroom
   today, but adding a per-request flag read for bitcoin means the 100x polling
   volume flows through it too — mitigated by client-side flag caching.

---

## 2. Architecture to handle 100x current traffic

100x of the payment path ≈ status polling from ~9K/day → ~900K/day (~10 rps
sustained, higher at peak), order/payment creation from ~20/day → ~2K/day, and
external settlement calls scaling proportionally.

**Design principles applied in this PR (code-level) and recommended (infra):**

### Implemented in this PR
- **Asynchronous settlement.** `POST /v1/orders/{id}/bitcoin-payment` records the
  payment *intent* (status `BITCOIN_PAYMENT_PENDING`) and returns `202 Accepted`
  immediately. A background scheduler (`BitcoinPaymentScheduler`, mirroring
  `WorkScheduler`) drains pending payments and calls the external provider off the
  request path. Slow provider latency never blocks a user request.
- **Timeouts on all outbound calls.** The bitcoin provider `HttpClient` is built
  with a connect timeout, and each request carries a per-request timeout, so a
  slow/hung provider fails fast instead of exhausting threads (directly addresses
  the third-party-service latency risk).
- **Bounded state machine.** New `StatusType` values
  (`BITCOIN_PAYMENT_PENDING` → `BITCOIN_PAYMENT_CONFIRMED` / `BITCOIN_PAYMENT_FAILED`)
  reuse the existing sequence-guarded status flow, so retries and out-of-order
  callbacks are rejected consistently.
- **Feature-flagged rollout.** `bitcoin_payment` flag (default off) lets us
  dark-launch and ramp per environment.

### Recommended alongside (infra / follow-up, called out for reviewers)
- **Horizontal scaling + statelessness.** CCOS is stateless (all state in MSSQL),
  so it scales horizontally behind the existing reverse proxy. Raise the helm
  `replicaCount` and CPU limits; the async scheduler should run as a **singleton
  worker** (or use DB row-locking / `SELECT ... WITH (UPDLOCK, READPAST)`) so
  multiple replicas don't double-process the same pending payment.
- **Cache the hot read.** Put a short-TTL cache (or CDN/edge cache) in front of
  `status/latest`; at 100x this read dominates. Cache flag values client-side to
  keep feature-flag-service flat.
- **Queue instead of DB-polling at very high scale.** The scheduler's
  DB-poll pattern is fine to ~10s rps; beyond that, move pending-payment handoff
  to the existing RabbitMQ so settlement workers scale independently of CCOS.
- **Circuit breaker on the provider.** No resilience library exists in the repo
  today; add Resilience4j (or equivalent) around the provider call so a provider
  outage sheds load gracefully rather than piling up retries.
- **DB connection pooling.** `DatabaseHelper` opens raw `DriverManager`
  connections per call. At 100x, introduce a pool (HikariCP) to avoid connection
  churn.

---

## 3. Existing issues to address alongside this feature

1. **`credit_card_meltdown` divide-by-zero (LIVE OUTAGE).** As of the analysis,
   `GET /v1/orders/{accountId}/status/latest` was failing ~100% because the
   `credit_card_meltdown` flag drives a seeded divide-by-zero. A defensive fix is
   in **PR #1** (`fix/credit-card-meltdown-graceful-degradation`). **This should
   merge/deploy — and/or the flag be disabled — before shipping bitcoin payment**,
   since bitcoin clients will poll the same status endpoint. Hard blocker if not
   resolved.
2. **No timeouts / circuit breaker on outbound HTTP.** `WorkScheduler` and
   `FeatureFlagClient` use `HttpClient` with no timeout. The bitcoin provider call
   in this PR sets timeouts; the pre-existing calls should be retrofitted the same
   way (follow-up).
3. **broker-service `GET /v1/instrument` 500s with no captured exception.** ~52
   failures/7d, no exception in the trace. Not on the payment path, but worth
   instrumenting so it isn't a blind spot as overall load grows.
4. **Raw per-call DB connections (no pool).** Fine today; a scaling risk at 100x
   (see above).
5. **third-party-service datastore is untraced** — a capacity black box per
   Bluebox. Confirm/instrument before relying on it (or a similar external
   provider) at higher volume.

---

## Request / status flow (this feature)

```
Client ──POST /v1/orders/{id}/bitcoin-payment──▶ CCOS
                                                  │ writes BITCOIN_PAYMENT_PENDING
                                                  └─▶ 202 Accepted (returns immediately)

BitcoinPaymentScheduler (background, off request path)
   ├─ drains orders at BITCOIN_PAYMENT_PENDING
   ├─ POST http://{BITCOIN_PAYMENT_SERVICE_HOSTANDPORT}/v1/payments  (timeout-bounded)
   └─ writes BITCOIN_PAYMENT_CONFIRMED or BITCOIN_PAYMENT_FAILED

Client ──GET /v1/orders/{accountId}/status/latest──▶ CCOS  (existing poll; now cache-friendly)
```

**Dependencies:** MSSQL (`ostroski-easytrade-db`), feature-flag-service (flag read),
and the new external `bitcoin-payment-service` (settlement) — reached via env var
`BITCOIN_PAYMENT_SERVICE_HOSTANDPORT`, defaulting off via the `bitcoin_payment` flag.
