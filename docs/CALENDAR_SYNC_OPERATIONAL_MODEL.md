# Calendar Sync Operational Model

## Architecture

- Pull scheduler is the correctness and reconciliation engine.
- Webhooks are a freshness accelerator.
- Effective model:
  - `Pull = correctness + eventual reconciliation`
  - `Webhook = latency optimization`

This is a hybrid model by design, not webhook-only.

## Configuration Model (Canonical)

All webhook lifecycle behavior now reads from one config tree:

```yaml
calendar:
  webhook:
    enabled: true|false
    shared-secret: ...
    provider:
      google:
        enabled: true|false
        address: https://.../integrations/calendar/webhooks/google
      microsoft:
        enabled: true|false
        address: https://.../integrations/calendar/webhooks/microsoft
        ttl-seconds: 7200
```

No component should read `calendar.webhook.google.address` anymore.

## Precedence Rules

Webhook enablement is evaluated with global+provider precedence:

- `calendar.webhook.enabled=false` => both providers disabled, regardless of provider flags.
- `calendar.webhook.enabled=true` + provider flag false => provider runs pull-only.
- `calendar.webhook.enabled=true` + provider flag true => provider may create/renew webhook subscriptions.

Equivalent expression:

`provider_webhook_enabled = calendar.webhook.enabled && calendar.webhook.provider.<provider>.enabled`

## Mode Semantics

### `PULL_ONLY`

Triggered when no provider has effective webhook enablement.

- No Google watch creation
- No Microsoft subscription creation
- No Google renewal attempts
- No Microsoft renewal attempts
- No webhook provider lifecycle traffic
- Pull scheduler remains active
- Incremental sync remains active
- Token refresh/rotation remains active
- Retry/backoff remains active

### `WEBHOOK_PLUS_PULL`

Triggered when at least one provider has effective webhook enablement.

- Webhooks accelerate freshness
- Pull scheduler still runs as correctness/backstop
- Watch/subscription renewals remain active only for enabled providers

## Startup Diagnostics

At startup the system logs active mode and provider state:

- `calendar_sync_mode=PULL_ONLY google_webhooks=disabled microsoft_webhooks=disabled`
- `calendar_sync_mode=WEBHOOK_PLUS_PULL google_webhooks=enabled microsoft_webhooks=disabled`

Warning-only validation is emitted when enabled provider URLs are risky:

- Microsoft enabled + non-HTTPS URL:
  - Graph subscription creation will fail.
- Any enabled provider URL that is localhost-like or non-HTTPS:
  - watch/subscription creation may fail in production-like environments.

Startup is not blocked; warnings are operational guidance.

## Environment Defaults

- `application-dev.yaml`: webhook disabled globally and per-provider (clean local pull-only).
- `application-prod.yaml`: webhook enabled globally and per-provider (hybrid webhook+pull).

## Freshness Expectations

- Production healthy (webhook+pull): near-realtime updates via webhook acceleration.
- Pull fallback: eventual consistency bounded by pull cadence (`calendar.sync.fixed-delay-ms`, default 30s).
- Degraded/backoff: bounded by retry schedule and provider/API error recovery path.

## Failure and Degraded Behavior

- If webhook setup fails for a connection, pull scheduler preserves correctness and converges state.
- Watchless/subscriptionless ACTIVE connections are observable and recoverable when webhooks are enabled.
- Disabling webhook mode intentionally removes renewal noise and provider webhook traffic for local development.

## Operational Metrics

Key gauges and counters to monitor mode/degradation:

- `calendar.sync.pull_only.active.count{provider=...}`
  - ACTIVE connections currently operating pull-only.
- `calendar.webhook.provider.enabled{provider=...}`
  - effective provider webhook flag after precedence (1/0).
- `calendar.watch.missing.count{provider=...}`
  - watchless/subscriptionless ACTIVE connections.
- `calendar.watch.active.count{provider=...}`
  - ACTIVE connections with healthy webhook channels/subscriptions.
