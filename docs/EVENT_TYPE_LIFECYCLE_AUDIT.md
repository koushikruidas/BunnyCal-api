# Event Type Lifecycle Audit

## Scope

This audit was performed on June 24, 2026 while implementing:

- Event type deletion guard for booking experiences
- ACTIVE experience delete guard

## Dependency graph

Event types are referenced by two different classes of consumers:

1. Live routing and presentation paths

- `BookingExperience.eventTypeId`
- Public booking resolution by `username + eventTypeSlug`
- Embed config resolution from experience -> event type
- Host-facing event type management, availability windows, reservation windows, RR stats

2. Historical and operational records

- `bookings.event_type_id`
- `event_sessions.event_type_id`
- `event_type_participants.event_type_id`
- availability / reservation window rows
- outbox, sync, metrics, diagnostics, notification payloads

The key distinction is whether the reference must stay live for future scheduling, or only remain readable for history / reconciliation.

## Implemented protections

- Event type delete is now blocked while any non-deleted booking experience still references the event type.
- The guard intentionally counts `DRAFT`, `ACTIVE`, and `ARCHIVED` experiences because:
  - `ACTIVE` would break embeds immediately.
  - `DRAFT` and `ARCHIVED` can still become live later.
- Experience delete is now blocked while the experience is `ACTIVE`; callers must archive first.

## Audit conclusions

### Protected live paths

- Public event type slug resolution already excludes soft-deleted event types.
- Experience activation already revalidates event type existence before going live.
- Embed config currently fails closed if the underlying event type disappears.
- Host-facing edit/read paths for windows, stats, and participant management already require `deleted_at IS NULL`.

### Historical paths intentionally keep deleted event types readable

- Booking and sync flows still use unfiltered event type lookups in some places.
- Session context hydration also tolerates deleted event types.

This appears intentional and is consistent with preserving historical bookings, sessions, notifications, and sync reconciliation after an event type has been retired.

## Remaining lifecycle gaps

### Event type slug reuse can still repoint old links

Current slug uniqueness is active-row scoped:

- `existsByUserIdAndSlugAndDeletedAtIsNull(...)`

That means:

1. Delete event type `sales-demo`
2. Recreate a new event type with slug `sales-demo`
3. Existing public links now resolve to a different event type

This is the same class of lifecycle risk already identified for experience slugs. No change was implemented here.

### Embed config still fails as not-found if invariants are bypassed outside the service layer

The new delete guard closes the normal owner-driven delete path. If data is mutated out-of-band, `EmbedQueryService` still degrades to `RESOURCE_NOT_FOUND` rather than returning a more specific lifecycle error. That is acceptable for now, but worth noting as a defense-in-depth limitation.
