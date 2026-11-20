# Windows service discovery and replica demo

This opt-in topology uses **Spring Cloud 2025.1.5** with the existing **Spring Boot 4.1.2 / Java 17** applications. Spring Cloud Commons 5.0.5 explicitly accepts Boot 4.0.x and 4.1.x; the compatibility verifier remains enabled. No Spring Security or sticky sessions are introduced.

## Start and manage

Create the PostgreSQL databases described in START_DEMO.md. Install Java 17+, Maven-wrapper prerequisites and Node.js. A 16 GB machine is recommended.

```bat
start-demo.cmd -Lendora -Discovery -Replicas 2 -Plan
start-demo.cmd -Lendora -Discovery -Replicas 2
start-demo.cmd -Discovery -Action Status
start-demo.cmd -Discovery -Action Restart -Instance borrower-2
start-demo.cmd -Discovery -Action Stop -Instance lender-2
start-demo.cmd -Discovery -Action Stop
```

`-Plan` is read-only: it does not build, start processes, create keys or change Kafka topics. Running without `-Discovery` preserves the original launcher. Discovery requires Lendora mode and supports one or two replicas per core service. Stop existing standalone processes before starting it.

The launcher builds each backend jar once, starts Eureka first, waits for primary-service migrations before starting secondary replicas, and waits for registrations before Gateway. Each Java process has a 384 MB maximum heap, 192 MB metaspace limit and two active processors. Runtime definitions, owned PIDs, process start times, current/previous stdout and stderr logs live under `runtime/discovery/`. Each supervisor restarts its own crashed child after 5, 15, 30 and then 60 seconds, stopping after five failures within ten minutes. `CrashLimit` requires diagnosis and an explicit restart. Stop operations check PID **and process start time** before terminating a child tree; unrelated Java processes are never selected by name.

| Component                       | Ports              | Hikari maximum per process |
| ------------------------------- | ------------------ | -------------------------- |
| Eureka / Gateway                | 8761 / 8090        | none                       |
| Borrower                        | 8081, 18081        | 4                          |
| Lender                          | 8084, 18084        | 4                          |
| Lifecycle                       | 8085, 18085        | 4                          |
| Lendora accounts                | 8086, 18086        | 4                          |
| Market data                     | 8080               | 5                          |
| Borrower / lender FIX           | 8082 / 8083        | 3                          |
| BorrowDesk / LendDesk / Lendora | 5173 / 5175 / 5176 | none                       |

Two replicas use at most **43 application database connections**. Keep at least 20 connections available for administration/migrations; PostgreSQL's usual 100-connection default is sufficient. Gateway, Eureka, PostgreSQL, Kafka and market data remain single points of failure in this local topology. This is horizontal scaling and worker failover for the core services, not infrastructure high availability.

## Routing, sessions and health

Clients use `http://localhost:8090` with `/borrower`, `/lender`, `/accounts` or `/market` before the existing `/api/v1/...` paths. Gateway removes that first segment. Frontend environment variables are passed by the launcher; `.env.discovery` files also support `npm run dev -- --mode discovery`. Business API request/response formats are unchanged. Browser requests must retain `credentials: include`.

Internal HTTP clients resolve logical names such as `http://lendora-service` directly through Eureka and Spring Cloud LoadBalancer. They do not call the public gateway. External market sources keep ordinary HTTP clients. The shared runtime source is added to each application's build, so existing per-service Maven commands work without first installing an unpublished shared jar.

Lendora sessions already reside in PostgreSQL, with the same host-scoped, HttpOnly, path `/` cookie on both replicas. Login, organization checks and logout work across instances. Backend authorization remains authoritative; Gateway removes caller-supplied provisioning/identity headers and only forwards public API namespaces. Internal provisioning, FIX recovery, lifecycle management and actuator endpoints are not exposed through gateway prefixes. Direct service ports and Eureka are for a trusted local development machine; restrict them with network policy when deploying remotely.

Eureka uses ten-second heartbeats, a 30-second expiry, five-second registry refresh and five-second server eviction. Load balancers actively check `/actuator/health/readiness` every five seconds with instance-list refresh. Readiness requires a ready application and working database after startup/migrations; Kafka or discovery outages do not cascade into database readiness. The custom Eureka health handler uses this same readiness group to avoid a circular dependency on Eureka's own remote health status. Allow up to **45 seconds** to remove a crashed replica. A request already in flight can fail; mutating HTTP requests are never automatically replayed by Gateway or application load balancing.

`X-Service-Instance` identifies the responding backend. Direct local actuator endpoints provide readiness, info, HTTP/JVM/Hikari metrics and `platform.worker.claims`, `platform.work.completed`, `platform.work.deduplicated` counters. Do not expose actuator metrics to the public gateway.

## Financial work and Kafka

Migration V22 creates `platform_worker_lease` and `platform_work_completion` in borrower_db and lender_db. Lifecycle shares lender_db and starts after the lender's migrations. Discovery-mode scheduled jobs elect a database lease owner, renew every ten seconds and expire after 45 seconds using database time and monotonically increasing fencing tokens. Financial cron tasks use stable job/loan/scheduled-period keys. A bounded per-loan transaction validates and locks the current fence, records completion, applies financial changes and writes the existing durable outbox together. Rollback removes the completion record too. A ten-second catch-up tick resumes missed scheduled work after crashes; existing successful loans/periods are skipped. Existing organization locks, ordered outbox heads and receiver inbox deduplication remain in place. Manual commands retain their existing request/event idempotency.

Consumer group IDs remain shared across replicas, with listener concurrency one per process. New lending topics are created with **six partitions** in discovery mode, including their DLT topics. Organization keys keep per-organization ordering. Existing topics are preserved; the launcher refuses to silently resize a smaller topic.

For existing one-partition topics, schedule a maintenance window:

1. Pause all UI/integration producers and scheduled financial commands. Let pending outboxes, FIX publications and Kafka consumer lag drain. Resolve failed/blocked outbox heads first; do not discard them.
2. In Relay, open **Overview → Lending topic migration → Check Kafka backlog** while the old services are running. Once the check passes and the database and FIX queues are drained, stop all backends, FIX adapters and the gateway. Keep Kafka and Relay online. Ensure no remote producer or consumer is still connected.
3. In Relay, select **Migrate lending topics**, confirm the maintenance checklist, and select **Start migration**. From `kafka-ui-portal/kafka-ui-portal`, the equivalent command is `node server/migrate-lending-partitions.js --confirm-drained`. Both check for local services and Kafka consumer lag, then increase partitions in place. The confirmation covers external writers and database outboxes, which Kafka alone cannot check.
   Dead-letter topics (`.DLT`) and the publish-only `borrower.events.v1` and `lender.events.v1` streams can retain records without a consumer offset. The utility reports and preserves those records. If an external consumer has committed an offset on one of these topics, its lag is still checked.
4. Restart in discovery mode and verify organizations still process events in order. Never resize topics with live backlog: changing partition count changes key-to-partition mapping.

Each FIX side deliberately has one QuickFIX/J process and one existing FileStore/session identity. Replicating that identity/store would corrupt session ownership. Scaling FIX requires separately provisioned sessions and fenced session ownership, outside this rollout. Notification polling remains the durable UI source; in-memory WebSocket broadcasts are not shared between replicas.

## Verification and failure drills

Run `powershell -NoProfile -File test-scripts/discovery-launcher-check.ps1` to check launcher syntax, PID ownership and read-only dry runs. Run each application's normal Maven tests. Run the new coordinator tests against an **isolated disposable PostgreSQL database**: they recreate their three `platform_*` test tables.

```bat
set PLATFORM_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/scalability_test
set PLATFORM_TEST_DATABASE_USER=postgres
set PLATFORM_TEST_DATABASE_PASSWORD=your-local-test-password
lender-service\mvnw.cmd -f platform-runtime\pom.xml test
api-gateway\mvnw.cmd -f api-gateway\pom.xml test
```

With the topology running, `node test-scripts/discovery-smoke.mjs` checks routing across both account replicas, edge restrictions and anonymous session behavior. For authenticated checks set `DISCOVERY_TEST_EMAIL` and `DISCOVERY_TEST_PASSWORD` to an existing disposable account; the script logs it in, checks the same session directly on each replica, exercises authorized workspace reads through Gateway, verifies organization isolation and logs out on one replica then confirms invalidation on both. It changes no financial balances. Set `DISCOVERY_LOAD_REQUESTS=200` with those credentials for eight concurrent authorized loan-list reads, reporting p50/p95 latency, errors and responding instances. Compare the output at one and two replicas.

To drill crash failover, kill only a recorded Java child PID (not its supervisor), repeat requests for at least 45 seconds, and inspect `X-Service-Instance` and Status. The supervisor should restart it; use `-Action Stop -Instance ...` when you want it to stay stopped. Test a lifecycle worker crash during valuation/accrual: only one completion per job/loan/period, one resulting financial effect/outbox event, and no stale owner writes are expected. Stop Kafka, submit a request, restart Kafka and check outbox recovery, inbox deduplication and UI notifications. Exercise locate, reservation, multi-collateral loan, return, margin, collateral release and buy-in closure through Gateway with two organizations. Compare HTTP latency, error rate and `hikaricp.connections.active` at one versus two replicas; confirm total connections remain within the stated budget.

## Validation recorded during implementation

- Eight new shared-runtime tests passed against PostgreSQL, including a killed JVM with an open transaction, rollback, 45-second lease expiry and exactly one replacement commit. Two gateway boundary tests passed.
- Live Eureka/Gateway tests passed for routing across two replicas, browser CORS preflight, shared login/logout, both lender and borrower registration/workspace access, and organization isolation. A killed account replica was excluded within the 45-second target.
- An eight-client, 200-request authorized-read smoke load completed with zero errors at both one and two replicas. Observed p95 was 132 ms and 124 ms respectively; this small local run is a functional capacity check, not a production benchmark. Direct actuator metrics confirmed four-connection core pools.
- A real Kafka broker created all 16 topics with six partitions, preserved and reported a deliberately undersized topic, and successfully increased it during the controlled offline migration.
- Java test reports total 244 tests: 241 passed and three existing failures remain in borrower/lender collateral-release tests and a lender margin-call fixture with a missing creation timestamp. The margin fixture failure was also reproduced from unchanged main; the collateral-release failures were previously reproduced there. Lendora, lifecycle, FIX bridge and market-data suites passed.
- PowerShell parsing, PID/start-time ownership and mutation-free dry runs passed using PowerShell 7 on Linux. Full Windows process supervision/restart still needs a Windows runtime check before rollout.
