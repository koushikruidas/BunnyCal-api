# Local Observability (Prometheus + Grafana)

## 1) Audit Summary (Before Integration)

Current telemetry state in this repo:

- Spring Boot Actuator is enabled with Prometheus endpoint exposure:
  - `management.endpoints.web.exposure.include=health,info,metrics,prometheus`
  - Metrics endpoint: `http://localhost:8080/actuator/prometheus`
- Micrometer instrumentation is already present across booking/sync/calendar flows.
- OpenTelemetry exists (`spring-boot-starter-opentelemetry`) and OTLP metrics export is configured to `http://localhost:4318/v1/metrics`.
- Docker Compose already had infra services (`postgres`, `redis`, `otel-collector`, `mailhog`).
- Structured logs already exist (`logback-spring.xml`) with app and SQL file appenders.

### Existing Metrics Already Useful for Dashboards

Booking and lifecycle:
- `booking_completed_total`
- `booking_completed_within_slo_total`
- `booking_completion_latency_seconds_bucket`
- `booking_cancellation_total{source=...}`
- `booking_pending_count`
- `booking_failed_total`
- `booking_conflicts_total`

Synchronization and reconciliation:
- `webhook_ingest_total`
- `sync_success_count`, `sync_failure_count`, `retry_count`
- `sync_reconcile_checked_total`, `sync_reconcile_requeued_total`, `sync_reconcile_errors_total`, `sync_reconcile_drift_detected_total`
- `sync_external_update_convergence_total`
- `sync_external_terminal_convergence_total`
- `calendar_sync_attempts_total{provider,result}`
- `calendar_sync_retries_total{provider,error_code}`
- `calendar_sync_latency_ms_bucket`
- `sync_latency_bucket`, `sync_time_to_success_ms_bucket`
- `outbox_lag_milliseconds_bucket`, `outbox_retries_total`

Provider health:
- `provider_latency_bucket{provider=...}`
- `token_refresh_success_count`, `token_refresh_failures_count`
- `sync_provider_failure_total`

Infrastructure (from JVM/Actuator/Micrometer):
- `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`
- `process_cpu_usage`
- `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_max`
- `http_server_requests_seconds_bucket`, `http_server_requests_seconds_count`
- `jvm_threads_live_threads`, executor metrics (if executor meters are active)

### Gaps Identified

- No explicit direct counters for `booking created`, `booking confirmed`, `booking rescheduled` transitions.
- No dedicated queue-depth gauge for sync job backlogs.
- Existing metrics still provide practical visibility using proxies (ingestion rate, convergence counters, retries, lag, terminal completion).

### Prometheus Scraping Compatibility

- Yes, scraping works immediately via `/actuator/prometheus`.
- No new instrumentation required for baseline dashboard visibility.

## 2) Minimal Local Architecture

- Application continues exposing `/actuator/prometheus`.
- Prometheus scrapes app metrics.
- Grafana reads Prometheus and auto-loads dashboards.
- OTel collector stays as-is (for OTLP telemetry path), but is not required for Prometheus pull scraping.
- Loki is intentionally **not** added in this lightweight setup.

## 3) What Was Added

- Compose services:
  - `prometheus` on `:9090`
  - `grafana` on `:3000`
- Persistent volumes:
  - `prometheus_data`
  - `grafana_data`
- Prometheus config:
  - `monitoring/prometheus/prometheus.yml`
- Grafana provisioning:
  - datasource: `monitoring/grafana/provisioning/datasources/prometheus.yml`
  - dashboard provider: `monitoring/grafana/provisioning/dashboards/dashboards.yml`
- Dashboards:
  - `monitoring/grafana/dashboards/booking-lifecycle.json`
  - `monitoring/grafana/dashboards/synchronization-engine.json`
  - `monitoring/grafana/dashboards/infrastructure-health.json`
  - `monitoring/grafana/dashboards/provider-health.json`

## 4) How to Run

1. Ensure app is running locally on port `8080`.
2. Start stack:
   - `docker compose up -d`
3. Verify Prometheus target:
   - Open `http://localhost:9090/targets`
   - `easyschedule-app` should be `UP`
4. Open Grafana:
   - URL: `http://localhost:3000`
   - Username: `admin`
   - Password: `admin`

## 5) Dashboard Purpose

1. **Booking Lifecycle**
   - Tracks booking terminal rates, cancellations, completion latency, failed sync outcomes.

2. **Synchronization Engine**
   - Tracks webhook ingest, provider attempts, reconcile outcomes, retries, lag/latency.

3. **Infrastructure Health**
   - Tracks JVM/process, DB pool pressure, thread/executor signals, request latency/errors.

4. **Provider Health**
   - Tracks provider latency, failure rates, token refresh outcomes, rate-limit behavior.

## 6) Known Limitations

- Some requested transition views use proxies because explicit counters for create/confirm/reschedule are not currently instrumented.
- Queue depth is shown via lag/retry pressure rather than a direct sync-job gauge.
- This setup is intentionally local/dev oriented: no HA, no long retention, no alertmanager routing.

## 7) Optional Lightweight Next Steps

- Add tiny explicit counters for `booking_created_total`, `booking_confirmed_total`, `booking_rescheduled_total`.
- Add one SQL-backed gauge for `calendar_sync_jobs` by status (pending/retrying/processing).
- Add a small logs panel later only if needed (Loki), not by default.
