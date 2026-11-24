# Local observability

Keep your existing Grafana at `http://localhost:3000`. Grafana reads metrics from a Prometheus data source and traces from a Tempo data source. The application exposes metrics for scraping and sends traces to an OTLP receiver.

## Use your existing data sources

- Add the targets in [prometheus.yaml](prometheus.yaml) to your Prometheus configuration and reload it. The applications expose `/actuator/prometheus` on ports 8080–8086 with the observability profile enabled.
- Set `OTEL_EXPORTER_OTLP_ENDPOINT` to your OTLP HTTP receiver if it differs from the default `http://127.0.0.1:4318`. The receiver should forward traces to the backend queried by Grafana.
- The Kafka panels use a Kafka exporter at `127.0.0.1:9308`. If you already run an exporter, use its scrape address instead. With no exporter, omit the `kafka` scrape job.

If you have standalone binaries for local backends, [prometheus.yaml](prometheus.yaml), [tempo.yaml](tempo.yaml), and [collector.yaml](collector.yaml) are ready for services on the same host. From the repository root, run each in its own terminal:

```text
prometheus --config.file=observability/prometheus.yaml --web.listen-address=127.0.0.1:9090 --storage.tsdb.path=observability/runtime/prometheus
tempo -config.file=observability/tempo.yaml
otelcol-contrib --config=observability/collector.yaml
```

## Start Lendora on Windows

Start PostgreSQL and stop any running demo backend JVMs before enabling instrumentation. From the repository root in PowerShell:

```powershell
$agent = Join-Path (Resolve-Path observability) 'runtime\opentelemetry-javaagent.jar'
New-Item -ItemType Directory -Force (Split-Path $agent) | Out-Null
Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.21.0/opentelemetry-javaagent-2.21.0.jar' -OutFile $agent
.\start-lendora.cmd -ObservabilityAgent "$agent"
```

Use `start-demo.cmd` with the same agent argument for the anonymous demo. The launcher assigns distinct names to both FIX adapters. Lender Flyway adds the shared outbox column before Lifecycle starts. Borrower and both FIX adapters apply their own migrations during normal startup.

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

1. In **Connections → Data sources**, add Prometheus with URL `http://127.0.0.1:9090` and Tempo with URL `http://127.0.0.1:3200`. Substitute your existing backend URLs if they differ. Grafana resolves these addresses from its own host.
2. Import [lending-operations.json](grafana/lending-operations.json) via **Dashboards → New → Import** and select your Prometheus data source. [datasources.yaml](grafana/datasources.yaml) is available if you manage Grafana through provisioning.
3. Check [Prometheus targets](http://localhost:9090/targets). Lendora is expected to be down if you started the demo without it.

## Follow a lending operation

1. Submit a loan valuation or recall from LendDesk against a demo loan. Verify the request is accepted.
2. In Grafana **Explore → Tempo**, search for recent traces for `lender-service`. The request trace should include the outbox worker's Kafka producer span and the lifecycle consumer span. Follow the lifecycle result back through Kafka to lender when the result is available.
3. Search the backend JSON logs for the same `trace_id`. Outbox publication messages include an event ID on lender and FIX adapter, and an outbox and aggregate ID on borrower. These logs stay in the service output.
4. On the operations dashboard, check HTTP traffic and latency. When the Kafka exporter is active, check lag by consumer group and topic and dead-letter offset increases.

Trace context is stored with new outbox rows and reused on asynchronous delivery and retries. Previously queued rows without trace context can still publish. Trace propagation is automatic for supported HTTP and Kafka clients. Raw FIX socket traffic has no trace context in this setup, so spans on either side of the FIX session are separate. Search Tempo with `{ span.lending.event_id = "YOUR_EVENT_ID" }` to find both, or search the adapter logs for the shared `event_id`. The Collector strips query strings, SQL text, and exception messages and stacks from exported traces.
