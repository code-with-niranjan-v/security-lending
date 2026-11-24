# Local observability

Use the existing Grafana at `http://localhost:3000`. This stack adds Prometheus on `9090`, Tempo on `3200`, and the OpenTelemetry Collector's HTTP receiver on `4318`. It does not start Grafana, Kafka, PostgreSQL, or the application.

## Start on Windows

From the repository root in PowerShell:

```powershell
docker compose -f observability/compose.yaml up -d
$agent = Join-Path (Resolve-Path observability) 'runtime\opentelemetry-javaagent.jar'
New-Item -ItemType Directory -Force (Split-Path $agent) | Out-Null
Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.21.0/opentelemetry-javaagent-2.21.0.jar' -OutFile $agent
.\start-demo.cmd -ObservabilityAgent $agent
```

Add `-Lendora` if you normally start the authenticated portals. Restart any existing backend JVMs to load the agent and the `observability` Spring profile. The launcher assigns distinct service names to the two FIX adapter instances. Lender Flyway adds the shared outbox column before Lifecycle starts. Borrower and both FIX adapter databases receive their own nullable columns through their usual startup migrations.

After Kafka is running, enable **host networking** in Docker Desktop (Settings → Resources → Network, Docker Desktop 4.34+) for the optional Kafka exporter:

```powershell
docker compose -f observability/compose.yaml --profile kafka up -d
```

The exporter needs to reach the broker's advertised `localhost:9092` listener. On Linux add `-f observability/compose.linux.yaml` after the base Compose file to make the host address available to Prometheus. The Kafka exporter uses Docker host networking there as well. If Docker runs in a separate network from the JVMs, set `OBSERVABILITY_HOST_GATEWAY` to the IP address those containers can use to reach the JVM host. Without the exporter, service dashboards and traces work, and the Kafka panels have no samples.

For services started individually, add the agent to each **application** JVM with `-javaagent:/absolute/path/to/opentelemetry-javaagent.jar`. Include the Spring profile `observability` alongside any existing profile. Set:

| Environment variable | Value |
| --- | --- |
| `OTEL_SERVICE_NAME` | Service name, for example `lender-service` |
| `OTEL_TRACES_EXPORTER` | `otlp` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://127.0.0.1:4318` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` |
| `OTEL_PROPAGATORS` | `tracecontext` |
| `OTEL_METRICS_EXPORTER` | `none` |
| `OTEL_LOGS_EXPORTER` | `none` |

Metrics use Actuator and Prometheus scraping. Use this profile on a local development network. Management metrics are served on the same interface as the application.

## Set up your existing Grafana

1. In **Connections → Data sources**, add Prometheus with URL `http://127.0.0.1:9090` and Tempo with URL `http://127.0.0.1:3200`. Those URLs are resolved by the Grafana server. For Grafana in Docker, join the `lending-observability_default` network and use `http://prometheus:9090` and `http://tempo:3200` instead.
2. Import [lending-operations.json](grafana/lending-operations.json) via **Dashboards → New → Import** and select your Prometheus data source. [datasources.yaml](grafana/datasources.yaml) is available if you manage Grafana through provisioning.
3. Check [Prometheus targets](http://localhost:9090/targets). Lendora is expected to be down if you started the demo without it.

## Follow a lending operation

1. Submit a loan valuation or recall from LendDesk against a demo loan. Verify the request is accepted.
2. In Grafana **Explore → Tempo**, search for recent traces for `lender-service`. The request trace should include the outbox worker's Kafka producer span and the lifecycle consumer span. Follow the lifecycle result back through Kafka to lender when the result is available.
3. Search the backend JSON logs for the same `trace_id`. Outbox publication messages include an event ID on lender and FIX adapter, and an outbox and aggregate ID on borrower. These logs stay in the service output.
4. On the operations dashboard, check HTTP traffic and latency. When the Kafka exporter is active, check lag by consumer group and topic and dead-letter offset increases.

Trace context is stored with new outbox rows and reused on asynchronous delivery and retries. Previously queued rows without trace context can still publish. Trace propagation is automatic for supported HTTP and Kafka clients. Raw FIX socket traffic has no trace context in this setup, so spans on either side of the FIX session are separate. Search Tempo with `{ span.lending.event_id = "YOUR_EVENT_ID" }` to find both, or search the adapter logs for the shared `event_id`. The Collector strips query strings, SQL text, and exception messages and stacks from exported traces.

Stop the observability containers with `docker compose -f observability/compose.yaml --profile kafka down`. The named volumes retain Prometheus and Tempo data.
