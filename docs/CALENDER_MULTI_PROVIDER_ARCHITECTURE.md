 ---
BunnyCal / BunnyCal — Calendar Multi-Provider Architecture Audit

Date: 2026-05-18
Scope: entire calendar/ module, sync/, booking/ cross-references, controllers, repositories, Flyway migrations, application config
Reviewer disposition: senior staff engineer evaluating "can this survive becoming a real multi-provider platform?"
  
---
1. Executive Summary

The architecture has the vocabulary of multi-provider design — CalendarProvider interface, CalendarProviderClient interface, CalendarProviderType enum (GOOGLE, MICROSOFT), provider parameter threaded
through every service, calendar.provider.mode config switch, @ConditionalOnProperty annotations on Google-specific beans. It looks like the bones were laid for plugin-style providers.

It is a Potemkin abstraction.

Almost every actual integration concern leaks Google semantics into the supposedly generic layers:

- The "generic" CalendarProvider interface (3 methods) is so abstract it is useless — anything provider-specific is hidden inside GoogleCalendarProvider and its GoogleApiClient collaborator.
- TokenRefresher is typed to GoogleApiClient — there is no abstraction for "refresh a token for any provider."
- CalendarOAuthService is named "CalendarOAuthService" but every method is *Google* (buildGoogleConnectUrl, handleGoogleCallback, googleConnectionStatus, disconnectGoogle,
  findGoogleConnectionIdByWebhookChannel).
- The webhook endpoint is /integrations/calendar/webhooks/google, the auth service is verifyGoogle(), the DTO is GoogleWebhookRequest, the controller takes 5 X-Goog-* headers explicitly, and routing
  dispatches to webhookIngestionService.ingestGoogle(...).
- The sync engine treats provider as a String field, but the only callers pass the literal "google". OutboxProcessor.processBatch(int, String provider) is called by nobody in main/ — the wiring that turns
  the BookingSyncWorker on assumes a single provider.
- Conferencing is not abstracted at all: Google Meet is hard-wired in HttpGoogleApiClient (conferenceData.createRequest.conferenceSolutionKey.type = "hangoutsMeet"); the schema stores one conference_url
  column per booking-provider mapping.
- The "calendar busy" projection (calendar_events) and its read path assume external_event_id is a single string namespace per (connection, provider). Microsoft (seriesMasterId, occurrenceId) and CalDAV
  (href, ETag) don't fit cleanly.
- One Google calendar per user (UNIQUE (user_id, provider)), no concept of multiple calendars per connection (work calendar + personal + meeting room), no is_primary flag, no per-calendar selection UX.

The codebase has a working sync framework — outbox → sync jobs → worker → reconciler with fencing and shadow decisions. That framework would survive a refactor. But the Provider abstraction layer is
incomplete and the call graph is full of Google-specific assumptions that will fight you on every new provider.

Honest read: adding Outlook is a 6–10 week project that touches ~25–40 classes and 5–8 migrations. Adding CalDAV is a roughly 2× that, because the framework assumes webhook-pushed delta sync with sync
tokens — CalDAV has neither. Adding Zoom is a different refactor entirely: the system has no conferencing abstraction; conferencing is a side-effect of "Google created a Meet link when we created the
event."
  
---
2. Architecture Audit — what's truly generic vs what's secretly Google

2.1 The abstraction inventory

┌───────────────────────────────────────────┬─────────────────┬────────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                   Layer                   │   Interface     │ Provider-agnostic? │                                                      Reality                                                      │
│                                           │     exists?     │                    │                                                                                                                   │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CalendarService (booking-side facade)     │ Yes             │ Yes (genuinely)    │ createEvent/updateEvent/deleteEvent/observeEvent — clean.                                                         │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CalendarProviderClient (booking↔provider  │ Yes             │ Yes (genuinely)    │ 5 methods, all take String provider. Reusable.                                                                    │
│ seam)                                     │                 │                    │                                                                                                                   │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CalendarProvider (low-level adapter)      │ Yes             │ Yes (interface     │ 3 methods. But there is only one implementation: GoogleCalendarProvider. No MicrosoftCalendarProvider. No         │
│                                           │                 │ only)              │ registry/router.                                                                                                  │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CalendarProviderType enum                 │ Yes             │ No                 │ GOOGLE, MICROSOFT. Only GOOGLE is used; MICROSOFT is a placeholder. No APPLE, no CALDAV, no ZOOM.                 │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ GoogleApiClient (provider HTTP adapter)   │ Yes             │ No (Google-only)   │ Names: fetchProviderUserId, listEventsIncremental, watchEvents, WatchChannel. Google semantics named explicitly.  │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ TokenRefresher                            │ "Generic" by    │ No                 │ Constructor: GoogleApiClient googleApiClient. Refresh call: googleApiClient.refreshAccessToken(refreshToken).     │
│                                           │ name            │                    │ There is no OAuthTokenClient interface.                                                                           │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CalendarOAuthService                      │ "Generic" by    │ No                 │ Every method is *Google*. Constants: GOOGLE_PROVIDER = CalendarProviderType.GOOGLE. Writes                        │
│                                           │ name            │                    │ connection.setProvider(GOOGLE_PROVIDER).                                                                          │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ExternalCalendarSyncClient                │ Yes             │ Yes (interface)    │ Two impls: NoopExternalCalendarSyncClient, GoogleIncrementalSyncObservationClient. Interface itself is clean      │
│                                           │                 │                    │ (fetchIncremental, fetchFull, SyncBatch, SyncTokenInvalidException).                                              │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ GoogleIncrementalSyncObservationClient    │ n/a             │ Yes (real impl)    │ @ConditionalOnProperty(name = "calendar.provider.mode", havingValue = "google", matchIfMissing = true).           │
│                                           │                 │                    │ Single-active-provider switch — see §3.                                                                           │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CalendarWebhookIngestionService           │ "Generic" name  │ No                 │ Single public method: ingestGoogle(...). Internal: literal "google" strings 9 times.                              │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CalendarWebhookAuthService                │ "Generic" name  │ No                 │ verifyGoogle(), verifyGoogleWatchNotification().                                                                  │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CalendarBusyTimeService                   │ Generic         │ Yes (genuinely)    │ Reads from calendar_events projection; no provider knowledge.                                                     │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ GoogleFreeBusyService                     │ n/a             │ No (Google-only)   │ Hardcoded GoogleApiClient dependency. No FreeBusyClient interface.                                                │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Conferencing                              │ Doesn't exist   │ No                 │ conferenceUrl is a string column populated by whatever Google returns. No ConferencingProvider interface. No Zoom │
│                                           │                 │                    │  path.                                                                                                            │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ calendar_events projection schema         │ Provider-tagged │ Half-generic       │ provider VARCHAR(32), external_event_id VARCHAR(255), cancelled BOOLEAN. Has the right shape but no               │
│                                           │                 │                    │ provider-specific metadata fields (recurrence series id, ETag, modified-occurrence linkage).                      │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ provider_event_projections (V40_0)        │ Provider-tagged │ Yes (genuinely)    │ provider, external_event_id, provider_sequence, provider_updated_at, provider_etag, payload_hash. Best            │
│                                           │                 │                    │ multi-provider artifact in the codebase.                                                                          │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ calendar_sync_jobs                        │ Provider-tagged │ Yes (mostly)       │ provider VARCHAR(32), unique (internal_ref_type, internal_ref_id, provider) — one job per (entity, provider).     │
│                                           │                 │                    │ Good shape.                                                                                                       │
├───────────────────────────────────────────┼─────────────────┼────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ calendar_event_mappings                   │ Provider-tagged │ Half               │ PK booking_id only (see V6_0) — one mapping row per booking, period. Provider stored as discriminator, but the PK │
│                                           │                 │                    │  forbids fan-out to multiple providers per booking.                                                               │
└───────────────────────────────────────────┴─────────────────┴────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

2.2 The "single-active-provider mode" trap

The clearest evidence the abstraction is incomplete: the system runs in one mode at a time, selected by calendar.provider.mode. Today it is google (or in-memory for tests). There is no multi mode. Beans
like GoogleCalendarProviderClient, GoogleIncrementalSyncObservationClient use @ConditionalOnProperty(... havingValue = "google", matchIfMissing = true) so the application starts with exactly one
CalendarProviderClient and exactly one ExternalCalendarSyncClient in the Spring context. To support Microsoft and Google simultaneously you need:

- A Map<CalendarProviderType, CalendarProviderClient> instead of a single injected bean.
- A ProviderRouter that picks the right client given the provider string on a sync job / connection.
- Removal of matchIfMissing = true (which silently defaults to Google).

None of this exists.

2.3 The "router that almost is"

DefaultCalendarService does CalendarProviderType.valueOf(command.provider().toUpperCase()) and then calls providerClient.createEvent(internalId, provider, idempotencyKey). That's a router for one provider
— it converts string to enum, then hands off to a singleton client. To make this actually route, providerClient must become a registry.
  
---
3. Hidden Coupling Findings

These are the load-bearing assumptions you do not see until you try to add a provider.

┌─────┬────────────────────────────────────────────────────────────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  #  │                                        Location                                        │                                             Hidden Coupling                                             │
├─────┼────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H1  │ TokenRefresher constructor                                                             │ Field-typed to GoogleApiClient. The "OAuth refresh" concern is welded to one provider.                  │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H2  │ CalendarOAuthService constructor                                                      │ Same — GoogleApiClient googleApiClient field. The "OAuth callback" concern is welded to Google's token   │
│     │                                                                                       │ endpoint.                                                                                                │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H3  │ HttpGoogleApiClient.CREATE_EVENT_URI =                                                │ The string "primary" is hardcoded. Users cannot pick which Google calendar (work vs personal) receives   │
│     │ "/calendar/v3/calendars/primary/events?sendUpdates=all&conferenceDataVersion=1"       │ events. Hardcoded conferenceDataVersion=1 forces Hangouts Meet on every event.                           │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H4  │ HttpGoogleApiClient.buildCreateEventBody()                                            │ Hardcodes "conferenceSolutionKey": {"type": "hangoutsMeet"}. There is no toggle, no per-event-type       │
│     │                                                                                       │ conferencing preference, no per-user choice.                                                             │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H5  │ CalendarConnection schema                                                             │ Single (user_id, provider) row. No display_name, no is_primary, no target_calendar_id. The schema        │
│     │                                                                                       │ assumes one connection per provider per user and that the provider has exactly one calendar.             │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                                                                       │ Massive query joins calendar_event_mappings cem ON ... AND cem.provider = 'google' — Google literally    │
│ H6  │ BookingRepository.findMeetingsForHost() / findUpcomingMeetingsForHost()               │ hardcoded. Same for calendar_sync_jobs j (AND j.provider = 'google'). 4 hardcoded 'google' strings in 2  │
│     │                                                                                       │ queries.                                                                                                 │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H7  │ PublicBookingService.ensureCalendarEventCreated()                                     │ String provider = "google"; — literal local variable. The whole confirm-path assumes one provider.       │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H8  │ PublicBookingService.availability()                                                   │ Reads only CalendarProviderType.GOOGLE connection status. Outlook-only users would get                   │
│     │                                                                                       │ CALENDAR_NOT_CONNECTED.                                                                                  │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H9  │ PublicBookingService hasActiveGoogleConnection(...)                                   │ Self-evident. Provider-typed.                                                                            │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                                                                       │ private static final String GOOGLE_PROVIDER = "google"; — the controller itself thinks "calendar         │
│ H10 │ CalendarIntegrationController                                                         │ integration" means "google". /integrations/calendar/google/connect,                                      │
│     │                                                                                       │ /integrations/calendar/google/callback, /integrations/calendar/webhooks/google — Google in the URL       │
│     │                                                                                       │ space.                                                                                                   │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H11 │ CalendarIntegrationController.disconnect(...)                                         │ if (!GOOGLE_PROVIDER.equalsIgnoreCase(provider)) return 400 "Unsupported provider"; — explicit gate.     │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H12 │ CalendarIntegrationController.status()                                                │ Map.of("google", oauthService.googleConnectionStatus(userId)) — response shape leaks Google to the       │
│     │                                                                                       │ frontend.                                                                                                │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H13 │ GoogleWebhookRequest DTO                                                              │ Provider-typed by name; controller takes 5 X-Goog-* headers explicitly. Microsoft Graph uses             │
│     │                                                                                       │ validationToken for subscription create and clientState in the body — neither would fit.                 │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                                                                       │ After token exchange: googleApiClient.watchEvents(...) to register a Google watch channel. This is the   │
│ H14 │ CalendarOAuthService.handleGoogleCallback()                                           │ only code path that subscribes to webhooks. Microsoft Graph subscriptions need a different lifecycle     │
│     │                                                                                       │ (3-day max TTL with renewal); CalDAV has no equivalent (polling only); Outlook has DELTA +               │
│     │                                                                                       │ subscriptions.                                                                                           │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                                                                       │ webhook_channel_id, webhook_resource_id, webhook_channel_expires_at, provider_sync_cursor — these field  │
│ H15 │ CalendarConnection columns                                                            │ names directly model Google's watch channel + sync token shape. Microsoft Graph has subscriptionId +     │
│     │                                                                                       │ expirationDateTime + clientState + notificationUrl, plus delta tokens; CalDAV has ctag/etag. The columns │
│     │                                                                                       │  can be reused, but their names will become misleading.                                                  │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H16 │ CalendarConnectionWriteService.advanceProviderCursor()                                │ The cursor model is "string token, advance monotonically." Microsoft delta tokens fit; CalDAV does not   │
│     │                                                                                       │ (per-calendar ctags + per-event etags). Apple iCloud has no delta — polling only.                        │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                                                                       │ Single externalEventId string; cancelled boolean; providerSequence / providerUpdatedAt / providerEtag /  │
│ H17 │ CalendarEventIngestionService.IncomingCalendarEvent                                   │ payloadHash. Microsoft recurring series produce seriesMasterId + occurrenceId + override-instance ids;   │
│     │                                                                                       │ representing them as a single externalEventId works but loses series identity. Outlook's "event          │
│     │                                                                                       │ modified" semantics for occurrences-of-series are richer than what's modeled.                            │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                                                                       │ The system generates its own ICS invites in addition to provider invites. With Google, this dual-invite  │
│ H18 │ BookingNotificationService / IcsInviteGenerator                                       │ strategy is tolerable. With Outlook (where the provider's own email is the authoritative invite), this   │
│     │                                                                                       │ will create duplicate calendar entries for guests. There is no per-provider toggle.                      │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H19 │ MeetingSummaryResponse (booking DTO)                                                  │ String provider, String conferenceUrl, String providerEventUrl — three flat strings per booking.         │
│     │                                                                                       │ Multi-provider sync (e.g., the same booking gets a Google event and a Zoom meeting) cannot be expressed. │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ H20 │ The event_types table has no conferencing-preference column                           │ No conferencing_provider, no auto_create_meet, no default_calendar_id. There is nowhere to say "this     │
│     │                                                                                       │ event type uses Zoom."                                                                                   │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                                                                       │ OAuth state payload format is provider-neutral but the state-validation pipeline is in                   │
│ H21 │ OAuthStateService.generate(userId, source, returnTo, bookingSessionId)                │ CalendarOAuthService.handleGoogleCallback which expects Google's (code, state) format. Microsoft's auth  │
│     │                                                                                       │ code flow uses identical params; that's fine. Apple Sign-In's auth flow uses id_token and is             │
│     │                                                                                       │ fundamentally different.                                                                                 │
├─────┼───────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                                                                       │ One mapping row per booking. If the same booking should publish to both Google and Outlook (because the  │
│ H22 │ calendar_event_mappings PK is booking_id                                              │ host has both connected), this PK forbids it. To support that, PK becomes (booking_id, provider).        │
│     │                                                                                       │ Migrating this is a real change because the existing claimBookingForSync, findMappingState,              │
│     │                                                                                       │ updateMappingWithEventId all key by booking_id only.                                                     │
└─────┴───────────────────────────────────────────────────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
  
---
4. Sync Architecture Findings

4.1 What survives a multi-provider expansion (the framework parts)

- calendar_sync_jobs schema is almost right: (internal_ref_type, internal_ref_id, provider) unique, desired_action ∈ CREATE/UPDATE/DELETE, status state machine, fencing-token version, exponential backoff.
  Adding Microsoft just means rows tagged provider='microsoft'.
- OutboxProcessor.processBatch(int, String provider) — the parameter is there. The dispatch model "one outbox event → one sync job per provider" is sound.
- BookingSyncWorker is generic over provider: it calls calendarService.createEvent(new CreateCalendarEventCommand(internalId, provider, idempotencyKey)) and the provider string is opaque. The worker would
  work for any provider that has a CalendarProviderClient implementation.
- BookingSyncReconciler.reconcile() is generic over provider — it loops jobs of any provider.
- provider_event_projections table is genuinely provider-neutral and has the right metadata (provider_sequence, provider_updated_at, provider_etag, payload_hash). This is the cleanest multi-provider
  artifact.
- ExternalCalendarSyncClient interface is clean. Pluggable.
- Fencing tokens, shadow decisions, canonicalizer, persisted snapshots — all provider-agnostic.

4.2 What does not survive

- BookingSyncWorkerScheduler.run() has no provider parameter. It just calls worker.processPending(batchSize). The claim query is provider-agnostic. Fine in principle. But OutboxProcessor.processBatch(int
  batchSize, String provider) is called by nobody in main/ — there's no @Scheduled wrapping that method. Either (a) it's called from a test or (b) the outbox→sync-job translation is happening another way and
  OutboxProcessor is dead code. Either way, the moment you add a second provider, you need two outbox-to-sync-job translations (one per provider) and a scheduler that issues both. That doesn't exist.
- The reconciler's calendarService.observeEvent(...) is single-provider per job. Fine — multi-provider means multiple jobs. But if you want a unified "is this booking in sync across all its providers?"
  check, that doesn't exist.
- Webhook ingestion is Google-only on the wire. CalendarWebhookIngestionService.ingestGoogle(...) is the public method. There is no ingestMicrosoft(...). Microsoft's notification payload, subscription
  renewal lifecycle, lifecycle notifications (subscriptionRemoved, missed), and resource path parsing are entirely different.
- The "incremental cursor" model assumes a single opaque string token per connection. Microsoft Graph fits (delta tokens are strings). CalDAV does not (per-resource ETags). Apple iCloud has no delta — your
  only option is poll-everything-and-diff, which means a full-resync every cycle.
- SyncTokenInvalidException is the only escape hatch. It's thrown by fetchIncremental to mean "your cursor was invalidated, do a full re-sync." That's exactly Google's 410 Gone model. Microsoft uses
  different semantics (tokenResync from @odata.nextLink); CalDAV has no equivalent because no delta exists. The exception type would need to be more nuanced.
- CalendarWebhookAuthService.verifyGoogle(...) uses HMAC-SHA-256 over a Google-shaped canonical payload, plus an alternative branch for X-Goog-Channel-Token watch notifications. Microsoft Graph signatures
  are clientState echo verification + validationToken round-trip on subscription create. Both go through this service today. They cannot.

4.3 Webhook subscription lifecycle gap

Google watch channels expire (max ~7 days). The codebase stores webhook_channel_expires_at but there is no scheduled job that renews them. A grep for webhookChannelExpiresAt shows only setters and getters.
After 7 days every watch channel silently dies and the system falls back to the 30-second polling scheduler. This will manifest as "calendar suddenly seems delayed" with no alert. Adding Microsoft (3-day
max expiry) makes this worse — renewal MUST be implemented before any provider whose webhooks expire is added in production.
  
---
5. Booking Flow Findings

5.1 The confirm() flow is Google-shaped

PublicBookingService.confirm():
1. Pre-flight countConflictsExcludingBooking (DB).
2. Pre-flight freeBusyService.busyIntervals(...) — Google FreeBusy API only. There is no OutlookFreeBusyService, no CalDavFreeBusyService, and freeBusyService is not behind an interface.
3. ensureCalendarEventCreated(bookingId) — local variable String provider = "google";. Hardcoded. The "claim + dispatch + finalize" pattern is provider-agnostic in shape, but the literal "google" means
   this flow can never create an Outlook event during synchronous confirm.

For a host with Outlook only:
- availability() would return CALENDAR_NOT_CONNECTED (it checks CalendarProviderType.GOOGLE).
- confirm() would try to call Google FreeBusy and fail.
- ensureCalendarEventCreated would attempt to claim a (bookingId, "google") mapping and call Google APIs that don't exist for this user.

The booking flow is structurally incompatible with non-Google hosts today.

5.2 Notifications

BookingNotificationService generates its own ICS attachments (IcsInviteGenerator) and emails them. Google additionally sends its own invite via sendUpdates=all. Today guests receive two invites
(acceptable; many systems do this). With Microsoft Graph, where sendUpdates is also a thing, this will double-deliver too. There is no per-provider toggle to disable BunnyCal's ICS when the provider is
sending invites itself.

5.3 The "primary attendee email" assumption

BookingNotificationService.handleOutboxEvent() consumes Booking events. Booking.guest_email/guest_name are scalars (audited in prior report). Multi-attendee + multi-provider would multiply this problem:
today there's no per-(attendee, provider) RSVP state; multi-provider means each provider has its own view of attendee responses.
  
---
6. UX / Onboarding Findings

6.1 The API surface tells frontend it lives in Google's world

- GET /integrations/calendar/google/connect — provider in the URL.
- GET /integrations/calendar/google/callback — same.
- DELETE /integrations/calendar/{provider} — almost generic, but rejects anything other than "google".
- GET /integrations/calendar/status — returns Map.of("google", "CONNECTED|DISCONNECTED|..."). To add Outlook, the response shape must change, which breaks any frontend that reads response.google.

6.2 Frontend assumptions encoded in backend

- appendQueryParam(returnTo, "integrationSuccess", GOOGLE_PROVIDER) — frontend's success page receives ?integrationSuccess=google. Frontend will likely have a switch on that literal.
- frontendSuccessRedirect / frontendErrorRedirect are configured on GoogleOAuthProperties only — no equivalent for other providers.
- "Connect calendar" is presumably a single button labeled "Google" in the UI; there is no provider-selection UX backend (no GET /integrations/calendar/available-providers, no scopes display per provider).

6.3 No "primary calendar" selection

Once Google is connected, the system reads/writes /calendars/primary. There is no UX to:
- Pick which Google calendar to read busy from (work vs personal).
- Pick which calendar to write events to.
- Mark a calendar as "private — read busy only, never write to it" (the typical privacy ask of users who have a Google personal calendar they don't want bookings on).

These are common-table-stakes for paid scheduling tools and will be a competitive gap.

6.4 No reconnect / re-auth flow

CalendarConnectionStatus includes ERROR, REVOKED, FAILED, DISCONNECTED. When status is anything but ACTIVE, the user must — presumably — re-connect. There's no scoped reconnect endpoint; the user re-runs
GET /integrations/calendar/google/connect, which re-runs the OAuth flow. That works for Google but the experience is "you've been disconnected — click here to authorize again," with no contextual hint.
There's also no "we noticed your token expired — here's a one-click renewal email" — but that requires a notification trigger that doesn't exist.

6.5 Booking-initiated calendar connection

buildGoogleConnectUrl(userId, source, returnTo, bookingSessionId) supports source=public-booking and returns the user to a booking flow. Good — onboarding via a guest's first booking is wired up. But it's
wired up for Google only. Public booking onboarding for Outlook hosts does not exist.
  
---
7. Scalability Risks (provider-pluralization specific)

┌─────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┬──────────────────────────┐
│  #  │                                                                                 Risk                                                                                  │         Severity         │
├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────┤
│     │ CalendarSyncScheduler.sync() is @Transactional and iterates all ACTIVE|SYNCING|FAILED|ERROR connections in one go, calling Google APIs synchronously inside the loop. │                          │
│ P1  │  Adding Microsoft means doubling this loop's work and serializing two providers under one DB transaction. Same as the reconciler issue from the prior audit —         │ High                     │
│     │ provider plurality multiplies it.                                                                                                                                     │                          │
├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────┤
│     │ Token refresh has a per-JVM accessTokenCache: ConcurrentHashMap<UUID, CachedAccessToken>. With 2 providers per user, cache keys are still UUID (connection-id), so it │                          │
│ P2  │  works — but TokenRefresher is wired only to GoogleApiClient. To refresh Microsoft tokens you need a Map<ProviderType, OAuthClient> and the cache becomes             │ Medium                   │
│     │ per-(connection, scope). Today's code can't express that.                                                                                                             │                          │
├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────┤
│ P3  │ Outbox-to-sync-job fan-out. OutboxProcessor.processBatch(batchSize, provider) is per-provider. The scheduler call site is missing in main/. Once you add Microsoft,   │ High                     │
│     │ you need two schedules: outbox→google jobs and outbox→microsoft jobs. The current model implicitly assumes one.                                                       │                          │
├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────┤
│ P4  │ Webhook subscription renewal. Google channels expire ~7 days; Microsoft Graph expires ≤3 days; both need a renewal cron. No such cron exists. Production failure      │ Critical (gets worse     │
│     │ mode: silent migration to polling fallback after expiry.                                                                                                              │ with each provider)      │
├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────┤
│     │ Provider rate limits. Google Calendar: ~500 req/100s/user. Microsoft Graph: ~10,000 req/10min/app (much tighter for /me). Apple iCloud CalDAV: undocumented but       │                          │
│ P5  │ throttles aggressively. The BookingSyncWorker.classify() only maps 429→RATE_LIMIT with generic backoff (SyncRetryPolicy). No per-provider rate-limit tracking, no     │ High                     │
│     │ per-provider quota budget. Adding any second provider means tail-latency surprises.                                                                                   │                          │
├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────┤
│ P6  │ CalendarSyncScheduler polls every 30s regardless of connection count. At N=1000 hosts × 3 providers each = 3000 connections; one tx every 30s with synchronous        │ High (scale-dependent)   │
│     │ provider calls is unworkable. There is no per-connection scheduling, no priority queue.                                                                               │                          │
├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────┤
│ P7  │ accessTokenCache is per-JVM. Multi-pod deployment = N token refresh fans, each provider rate-limited independently. With 3 pods × 1000 hosts × 3 providers = 9000     │ Medium                   │
│     │ refresh attempts per token TTL cycle in the worst case. Need a distributed token cache (Redis) before plural providers.                                               │                          │
├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼──────────────────────────┤
│ P8  │ The calendar_events projection is unbounded across all providers. No retention policy. With 3 providers feeding events, growth triples.                               │ Low–Medium               │
└─────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┴──────────────────────────┘
  
---
8. Security & Token Management Risks

┌─────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┬────────────┐
│  #  │                                                                                        Risk                                                                                         │  Severity  │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│ S1  │ AesGcmTokenCipher (TokenCipher) is shared across providers. Good. The cipher itself looks correct (AES-GCM with IV + versioned ciphertext prefix per the architecture doc).         │ —          │
│     │                                                                                                                                                                                     │ (positive) │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│ S2  │ Refresh tokens for all providers live in one column refresh_token_ciphertext on calendar_connections. Sufficient as long as the cipher is the same. Fine.                           │ —          │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│ S3  │ Webhook shared secret is a single calendar.webhook.shared-secret env var used for both verifyGoogle HMAC and verifyGoogleWatchNotification channel token. With multiple providers,  │ Medium     │
│     │ a single shared secret means a compromise of one provider's webhook receiver compromises all. Per-provider secrets are the correct design.                                          │            │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│ S4  │ Webhook signature verification is opt-in (calendar.webhook.auth.require-signature: false default). The legacy path is X-Webhook-Secret shared-secret in a plain header. Not great   │ Medium     │
│     │ today, dangerous when more provider notifications start landing.                                                                                                                    │            │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│ S5  │ OAuth scope storage is TEXT[] per connection. Adding providers with much larger scope lists (Microsoft Graph has dozens) works at column level but means user-visible scope-grant   │ Low        │
│     │ UX needs per-provider scope catalogs.                                                                                                                                               │            │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│ S6  │ CalendarOAuthService.copyForUpdate() uses reflection to set the id field on CalendarConnection (Field idField = CalendarConnection.class.getDeclaredField("id");                    │ Low–Medium │
│     │ idField.setAccessible(true);). This is a smell — when the entity is refactored (e.g., for multi-calendar-per-connection), reflection breaks silently.                               │            │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│ S7  │ The frontend success redirect appends ?integrationSuccess=google to returnTo. returnTo is validated to be a relative path. Good. But there's no anti-CSRF on the OAuth callback     │ —          │
│     │ beyond the state token — which is fine, since state is HMAC-signed and includes the user id. No issue.                                                                              │            │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│     │ OAuth state is stored in a signed payload (OAuthStatePayload). Multi-provider needs state.provider so the callback dispatcher knows which provider's token endpoint to talk to.     │            │
│ S8  │ Today that's implicit (the URL says /google/callback). When you have /microsoft/callback you need state to carry provider or each callback must be dedicated. Current state schema  │ Medium     │
│     │ doesn't carry provider.                                                                                                                                                             │            │
├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────┤
│ S9  │ Token revocation: disconnectGoogle() calls connectionWriteService.markFailure(... REVOKED ...) — it does not call Google to actually revoke the OAuth grant. The grant sits live on │ Medium     │
│     │  Google's side until the user manually revokes. Compliance/right-to-erasure exposure.                                                                                               │            │
└─────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┴────────────┘
  
---
9. Provider-by-Provider Readiness

9.1 Microsoft Outlook / Microsoft 365 (Microsoft Graph)

Readiness: 3/10. The framework parts (sync jobs, projections, fencing) survive. But:

┌──────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│     Concern      │                                                                                       Status                                                                                        │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ OAuth flow       │ Need a new MicrosoftOAuthService + new controller routes (/microsoft/connect, /microsoft/callback). The current CalendarOAuthService is 100% Google-named.                          │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Token refresh    │ TokenRefresher typed to GoogleApiClient — must be refactored to a Map<ProviderType, OAuthClient> or one TokenRefresher per provider.                                                │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Webhook          │ Microsoft Graph subscription lifecycle: create with 3-day TTL, validationToken round-trip on creation, clientState echo verification on notifications, lifecycle notifications      │
│ subscriptions    │ (subscriptionRemoved, missed, reauthorizationRequired). None of these are implemented. Webhook renewal is not implemented (also missing for Google).                                │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Delta sync       │ Microsoft delta tokens fit the provider_sync_cursor model loosely; the SyncTokenInvalidException-on-expiry pattern works.                                                           │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Recurrence       │ Microsoft has different recurrence semantics (SeriesMaster + Occurrence + Exception). The current IncomingCalendarEvent model represents an event with single externalEventId.      │
│                  │ Series modifications will appear as multiple ingestion events with subtle linkage that the projection doesn't preserve.                                                             │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Timezone         │ Outlook stores events with named timezones plus DST exceptions. The system converts to UTC Instant. Acceptable for busy-time but lossy for round-trips.                             │
│ handling         │                                                                                                                                                                                     │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Conferencing     │ Teams meeting requires isOnlineMeeting=true + onlineMeetingProvider=teamsForBusiness on event creation. The hardcoded hangoutsMeet in HttpGoogleApiClient is Google-specific; a     │
│ (Teams)          │ separate MicrosoftApiClient will be needed with its own conferencing payload.                                                                                                       │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ICS              │ When BunnyCal sends ICS + Outlook sends Graph invite, attendee gets two emails. Need a per-provider notification suppression toggle.                                                │
│ double-invite    │                                                                                                                                                                                     │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Free/busy        │ Microsoft has /me/calendar/getSchedule — needs an OutlookFreeBusyService; GoogleFreeBusyService is not extractable.                                                                 │
├──────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Frontend         │ "Connect Outlook" button, status field microsoft, status response shape break, OAuth callback route, success-redirect with integrationSuccess=microsoft.                            │
└──────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

Effort estimate: 6–10 engineer-weeks. Half of that is refactoring the abstraction layer before writing any Microsoft code.

9.2 Apple Calendar / CalDAV

Readiness: 1/10. The current architecture assumes:
- REST/JSON APIs (CalDAV is XML over HTTP).
- OAuth (CalDAV is HTTP Basic auth, or per-user app-specific passwords for iCloud).
- Delta sync (CalDAV has CTags per calendar collection + ETags per resource — incremental but very different model; doesn't fit provider_sync_cursor).
- Webhooks (CalDAV has no push — polling only; some servers support WebDAV-Sync REPORT for change discovery).
- Provider-specific stable event IDs (CalDAV uses ICS UID field + resource href).

The "ExternalCalendarSyncClient" interface would accept a CalDAV implementation if you treat polling as fetchIncremental always returning the full delta. But you'd be re-using the framework with completely
different semantics underneath. Subscription lifecycle, watch channels, sync tokens — all conceptually absent for CalDAV. Adding CalDAV means:
- New auth path (app-specific password vault, not OAuth).
- A polling-only scheduler at higher frequency.
- Full re-implementation of CalendarEventIngestionService.IncomingCalendarEvent to map ICS VEVENT properties.
- ICS parser dependency (e.g., iCal4j).
- Per-calendar polling (CalDAV exposes multiple calendar collections per principal).
- Conference URL is non-existent (FaceTime URL parsing? No standard).

Effort estimate: 10–16 engineer-weeks. Genuinely a separate integration model.

9.3 Zoom (conferencing, not calendar)

Readiness: 1/10. Zoom is not a calendar provider — it's a conferencing provider. The architecture treats conferencing as a side-effect of "Google created a Meet link when we created the event." There is
no:
- ConferencingProvider interface.
- event_types.conferencing_provider column.
- booking.conferencing_provider column (today, conference URL is stored on calendar_event_mappings.conference_url — coupled to the calendar provider mapping).
- Workflow for "create Zoom meeting, then attach the link to the calendar event of whichever provider the host uses."
- Per-user Zoom OAuth connection (the schema only models CalendarProviderType connections).

To support Zoom you need:
- New ZoomConnection table or extend calendar_connections with a connection_kind ∈ CALENDAR | CONFERENCING discriminator.
- New conferencing_provider enum.
- A ConferencingProvider interface (createMeeting/updateMeeting/cancelMeeting).
- Booking creation flow that orchestrates "Zoom create → then Calendar create with Zoom link in description/location."
- Cancellation flow that cancels both.
- Per-event-type conferencing preference.
- A separate sync lifecycle (Zoom recurring meeting IDs vs single meeting IDs).

Effort estimate: 4–7 engineer-weeks for a minimum Zoom-as-conferencing path. Less than Microsoft because no calendar-sync engine is needed — but it's an entirely new axis of abstraction
(ConferencingProvider) that doesn't exist today.

9.4 Generic future provider extensibility

Readiness: 2/10. The interfaces exist but the registry, the router, the multi-bean wiring, the per-provider config separation, the per-provider webhook routes, the per-provider OAuth state, the
per-provider scope catalog — none of it. Every new provider today is a copy-paste-and-rename of Google-flavored code.
  
---
10. Extensibility Scorecard

┌────────────────────────────────────┬─────────────┬────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│             Dimension              │   Score     │                                                                   Justification                                                                    │
│                                    │   (1–10)    │                                                                                                                                                    │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Provider abstraction quality       │ 3           │ Interfaces exist but Google is welded into TokenRefresher, CalendarOAuthService, GoogleFreeBusyService, webhook ingestion, controllers. Only one   │
│                                    │             │ implementation of each.                                                                                                                            │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Sync architecture quality          │ 7           │ The job model, fencing, reconciler, projections, snapshot canonicalizer are well-designed and provider-agnostic. Best part of the codebase.        │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Extensibility                      │ 3           │ Single-active-provider @ConditionalOnProperty mode. No provider registry. No router.                                                               │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Reliability                        │ 6           │ Token refresh failure-cooldown, sync retry policy, dedup, replay capture — all present. No webhook subscription renewal cron. Critical operational │
│                                    │             │  gap.                                                                                                                                              │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Microsoft readiness                │ 3           │ Schema half-fits, sync framework reusable, but every OAuth/webhook path needs new code.                                                            │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Apple readiness                    │ 1           │ Architectural mismatch. CalDAV is alien to current design.                                                                                         │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Zoom readiness                     │ 1           │ Conferencing not abstracted at all. New axis entirely.                                                                                             │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ API design quality (integrations)  │ 3           │ URLs and DTOs literally say "google." Response shapes leak provider names.                                                                         │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Booking flow extensibility         │ 2           │ confirm() is hardcoded to Google. availability() only checks Google connections.                                                                   │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Production readiness               │ 6           │ Token refresh, OAuth, fencing, retry — all functional. Webhook renewal gap is the biggest reliability risk.                                        │
│ (single-provider)                  │             │                                                                                                                                                    │
├────────────────────────────────────┼─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Production readiness               │ 2           │ Not close. The single-active-mode bean wiring means you can't run with two providers without a refactor.                                           │
│ (multi-provider)                   │             │                                                                                                                                                    │
└────────────────────────────────────┴─────────────┴────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
  
---
11. Gap Analysis

┌─────────────────────────────────────────────────┬─────────────────────────────────────────────┬───────────────────────────┬──────────────────────────┬─────────────────────────┬─────────────────────┐
│                   Capability                    │                  Current                   │        Microsoft         │          Apple           │            Zoom            │       Generic       │
├─────────────────────────────────────────────────┼────────────────────────────────────────────┼──────────────────────────┼──────────────────────────┼────────────────────────────┼─────────────────────┤
│ CalendarProvider registry (Map by type)          │ None — single bean                      │ Required                         │ Required              │ n/a                      │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Per-provider OAuthClient + TokenRefresher        │ Hardcoded GoogleApiClient               │ Required                         │ Required (different   │ Required (Zoom OAuth)    │ Required          │
│ refactor                                         │                                         │                                  │ auth model)           │                          │                   │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Per-provider webhook controller/auth             │ Google-only                             │ Required                         │ n/a (no webhooks)     │ Required (Zoom webhooks) │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Webhook subscription renewal cron                │ Missing today                           │ Required (3-day)                 │ n/a                   │ Required (Zoom subs have │ Required          │
│                                                  │                                         │                                  │                       │  TTLs)                   │                   │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ CalDavSyncClient polling model + ICS parser      │ None                                    │ n/a                              │ Required              │ n/a                      │ Required for      │
│                                                  │                                         │                                  │                       │                          │ pull-only         │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ ConferencingProvider abstraction                 │ None                                    │ n/a (Teams via Graph)            │ n/a                   │ Required                 │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Per-event-type conferencing preference           │ None                                    │ Required                         │ n/a                   │ Required                 │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Multi-calendar per connection (work + personal)  │ One per (user, provider)                │ Required                         │ Required              │ n/a                      │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ is_primary / target_calendar_id columns          │ None                                    │ Required                         │ Required              │ n/a                      │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Frontend provider-selection UX backend           │ None                                    │ Required                         │ Required              │ Required                 │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ GET /integrations/calendar/status multi-provider │ {"google": "..."}                       │ Required (breaking change)       │ Required              │ Required                 │ Required          │
│  response                                        │                                         │                                  │                       │                          │                   │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Per-provider URL routes                          │ /google/... only                        │ Required                         │ Required              │ Required                 │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ OAuth state carries provider                     │ No                                      │ Required                         │ Required              │ Required                 │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Distributed access-token cache (multi-pod safe)  │ Per-JVM ConcurrentHashMap               │ Needed at scale                  │ Needed                │ Needed                   │ Required for      │
│                                                  │                                         │                                  │                       │                          │ production        │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Per-provider rate-limit budgets                  │ None                                    │ Required                         │ Required              │ Required                 │ Required          │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Provider-specific scopes catalog                 │ Hardcoded Google scopes in              │ Required                         │ Required (Apple       │ Required                 │ Required          │
│                                                  │ GoogleOAuthProperties                   │                                  │ Sign-In differs)      │                          │                   │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Recurring series modeling (master + occurrence   │ Flat externalEventId only               │ Required for correct Outlook     │ Required for iCloud   │ n/a                      │ Required          │
│ linkage)                                         │                                         │ sync                             │ series                │                          │                   │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Provider OAuth revocation on disconnect          │ Not called                              │ Required                         │ Required              │ Required                 │ Required          │
│                                                  │                                         │                                  │                       │                          │ (compliance)      │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Hardcoded 'google' SQL purge                     │ 4 sites in BookingRepository, 2 in      │ Required                         │ Required              │ Required                 │ Required          │
│                                                  │ PublicBookingService                    │                                  │                       │                          │                   │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ Per-provider notification suppression (avoid     │ None                                    │ Required                         │ Required              │ n/a (Zoom sends invites  │ Required          │
│ double ICS)                                      │                                         │                                  │                       │ separately)              │                   │
├──────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────────┼───────────────────────┼──────────────────────────┼───────────────────┤
│ calendar_event_mappings.PK = booking_id →        │ Single-provider PK                      │ Required if same booking syncs   │ Same                  │ n/a                      │ Required          │
│ (booking_id, provider)                           │                                         │ to multiple providers            │                       │                          │                   │
└──────────────────────────────────────────────────┴─────────────────────────────────────────┴──────────────────────────────────┴───────────────────────┴──────────────────────────┴───────────────────┘
  
---
12. Recommended Refactors (prerequisites — do these BEFORE any new provider)

Phase A — Cleanup (1 week)

1. Purge hardcoded 'google' literals in BookingRepository.findMeetingsForHost() / findUpcomingMeetingsForHost(). Replace with :provider parameter and pass through. 4 occurrences.
2. Purge String provider = "google"; from PublicBookingService.ensureCalendarEventCreated(). Plumb the provider through from the caller / connection lookup.
3. Move GoogleOAuthProperties to provider-specific-properties/google.yaml and add a registry-aware CalendarOAuthProperties that holds a map of provider → properties.

Phase B — Provider registry (2–3 weeks)

4. Introduce CalendarOAuthFlow interface with implementations per provider. CalendarOAuthService becomes a router.
5. Introduce OAuthTokenClient interface (refresh / exchange / fetchProviderUserId). TokenRefresher becomes provider-agnostic, injected with a Map<ProviderType, OAuthTokenClient>.
6. Introduce FreeBusyClient interface. Wrap GoogleFreeBusyService. Re-key PublicBookingService.availability() to iterate over all connected providers.
7. Introduce WebhookHandler per provider with provider-specific verification + ingest. Controller routes become /webhooks/{provider} with strategy lookup.
8. Add provider field to OAuth state payload. OAuth callback becomes /integrations/calendar/oauth/callback and dispatches by state's provider.
9. Refactor CalendarIntegrationController connect/status/disconnect to be {provider}-parameterized. Frontend /status returns Map<provider, status> instead of {"google": ...}.

Phase C — Operational hardening (2 weeks; non-negotiable for production)

10. Webhook subscription renewal cron. Scan calendar_connections WHERE webhook_channel_expires_at < NOW() + 24h and renew. Must exist for Google even before adding any new provider; today this is silently
    broken on a 7-day timer.
11. Distributed access-token cache in Redis. Multi-pod safety.
12. Per-provider rate-limit budget service. Track outstanding/recent requests per (provider, connection). Reject/queue when budget exceeded. Adds ~150 LoC, prevents tail-latency fires.
13. OAuth revocation on disconnect. Actually call the provider's revoke endpoint when status flips to REVOKED.

Phase D — Schema additions for multi-calendar + conferencing (3–4 weeks)

14. Add calendar_connections.is_primary BOOLEAN and remove the UNIQUE (user_id, provider) constraint. Replace with a partial index ensuring at most one primary per (user_id, provider). Allows multiple
    Google calendars per user.
15. Add calendar_connections.target_calendar_id (varchar, nullable) — for "write events here," default primary.
16. Refactor calendar_event_mappings PK from (booking_id) to (booking_id, provider). Enables one booking to publish to multiple providers (Google + Outlook).
17. Add event_types.conferencing_provider enum (NONE | GOOGLE_MEET | TEAMS | ZOOM | CUSTOM_URL). Add event_types.default_calendar_connection_id (FK, nullable) for per-event-type calendar selection.
18. Introduce ConferencingProvider interface and Zoom connection table. Conferencing becomes a separate side-effect coordinated alongside calendar event creation.

Phase E — Provider-specific (per provider, 4–10 weeks each depending)

19. Outlook (Microsoft Graph) — net new MicrosoftOAuthClient, MicrosoftApiClient, MicrosoftSyncClient, MicrosoftWebhookHandler, recurrence-series model adapter. Half the work is per-Phase B/C/D refactor;
    the other half is real Graph integration code.
20. Zoom — ZoomOAuthClient, ZoomConferencingProvider. No sync framework needed; just lifecycle hooks on BOOKING_CONFIRMED/UPDATED/CANCELLED events.
21. Apple / CalDAV — CalDavSyncClient, ICS parser, polling scheduler, app-specific-password auth path. Largest single project.

  ---
13. Recommended Integration Order

Optimizing for monetization, reliability, and operational simplicity (your stated priorities), not feature count:

Order:

1. Phase A (cleanup) + Phase C (operational hardening), in parallel. ~2 weeks. Webhook renewal is the highest single-provider reliability bug today.
2. Phase B (provider registry). ~3 weeks. Pure refactor, no new feature.
3. Phase D items 14, 15, 17, 18 (the multi-calendar + conferencing schema). ~3 weeks. Item 16 (mapping PK refactor) can wait until you actually publish to two providers per booking — it's painful, skip if
   not needed yet.
4. Zoom integration as a ConferencingProvider only. ~4 weeks. This is your highest-revenue integration and the cheapest to deliver because you don't need new sync engine code — just lifecycle hooks. Most
   paying users want Zoom regardless of calendar provider.
5. Microsoft Outlook / Graph. ~8 weeks. Highest-impact calendar integration after Google. Pairs naturally with Teams conferencing.
6. Apple / CalDAV — only after a customer asks loudly enough. ~14 weeks. Worst ROI per engineering week. Most Apple users with paid scheduling tools also have a Google account; deflect.

What NOT to attempt yet

- Do not add Microsoft on top of the current Google-typed TokenRefresher / CalendarOAuthService. You'll either (a) copy-paste Google's flow and rename to Microsoft, doubling your technical debt, or (b)
  hack a branch into the Google service. Both are decision-tax that never gets repaid.
- Do not attempt CalDAV without first implementing a polling-friendly sync scheduler. The current 30-second CalendarSyncScheduler plus webhook-driven CalendarWebhookIngestionService is not the right shape.
- Do not attempt "Zoom on top of calendar provider" without first extracting ConferencingProvider. Coupling Zoom into the calendar provider abstraction is a multi-year regret.
- Do not let any new provider land without OAuth-state carrying provider id and per-provider OAuth callback routes. It's the cheapest possible cleanup and saves a class of bugs.

What must be hardened before expanding providers (table stakes)

- Webhook subscription renewal cron (operational).
- Distributed access-token cache (multi-pod safety).
- Per-provider rate-limit budgets (operational).
- OAuth revocation on disconnect (compliance).
- Purge hardcoded 'google' literals (correctness).
- Frontend status API multi-provider response shape (breaking change — do it once, atomically).

  ---
14. Final Verdict

▎ "How ready is BunnyCal to support multiple calendar providers cleanly and reliably beyond Google Calendar?"

Not ready.

The intent is visible in the codebase — interfaces named CalendarProvider, CalendarProviderClient, ExternalCalendarSyncClient; a CalendarProviderType enum that already includes MICROSOFT; a
calendar.provider.mode config knob; per-bean @ConditionalOnProperty switches. Someone thought about it. But the implementation never followed through.

The sync framework (jobs, projections, fencing, reconciler, idempotency, dedup) is genuinely provider-agnostic and will survive expansion. It is the best engineered part of the system.

Everything else — OAuth flow, token refresh, webhook ingestion, webhook auth, free/busy retrieval, calendar event creation, controller routing, status reporting, conferencing — is named generically but
typed and wired to Google specifically. The single-active-provider @ConditionalOnProperty mode is the smoking gun: this system can run with mode=google or mode=in-memory, but not with mode=google AND
mode=microsoft. There is no multi-bean Provider Registry.

Conferencing (Zoom) is the worst gap — the abstraction layer doesn't exist at all. Conferencing today is "whatever Google Meet link Google generated when we created the event." This is not a multi-provider
scheduling platform; it is a single-vendor scheduling tool that happens to be modular in a few interface signatures.

What it would take to genuinely support multi-provider

- Phase A + B + C + D, before any new provider lands: ~10 engineer-weeks of refactor + operational hardening. Required.
- Each new provider then costs 4–14 weeks depending on (Zoom 4, Microsoft 8, Apple 14). The refactor amortizes across them.

Highest-priority warnings

1. Webhook subscription renewal cron is missing today. This is a current reliability bug; Google watch channels expire ~7 days. The system silently falls back to 30s polling. No alarm. Fix this regardless
   of multi-provider plans.
2. The 'google' string is hardcoded in 4 BookingRepository projection queries. Every multi-provider feature trips on this.
3. OAuth state does not carry provider. Adding any second provider requires a state-schema migration or per-provider callback routes (forks the OAuth code path). Decide which before writing Microsoft code.
4. TokenRefresher is typed to GoogleApiClient. It is the cornerstone of the auth pipeline and it is hardwired to one vendor. Every new provider triggers a refactor here. Do this refactor once, in Phase B,
   not once per provider.
5. There is no ConferencingProvider interface. Zoom support cannot be a small change.

Likely technical-debt disasters

- The "almost generic" naming. CalendarOAuthService calls itself generic but has 7 Google-named methods. The next engineer will think it is generic, copy-paste it to add Microsoft, and end up with two
  parallel "OAuth services" that diverge over time. Rename CalendarOAuthService → GoogleCalendarOAuthService now; force the issue.
- The single mapping row per booking. calendar_event_mappings.PK = booking_id will be the constraint that forces a painful migration when the first user has both Google and Outlook connected and expects
  the booking to land on both.
- Bookings that wait synchronously for sync completion (awaitMappingCreated, 5s timeout in PublicBookingService). With more providers, this either becomes per-provider serial (multiplies latency) or
  per-provider parallel (multiplies failure modes). Neither is currently designed.
- The frontend response shape {"google": "CONNECTED"} baked into the status endpoint. This will break every frontend client the day Microsoft lands. Do the breaking change once, atomically, in Phase B —
  not later.
- The 30s polling fallback in CalendarSyncScheduler is currently the only thing keeping calendar data fresh when Google watch channels expire silently. Adding a second provider, with no webhook renewal
  cron, doubles the silent-data-loss risk.

  ---
Bottom line: The codebase has the blueprint of a multi-provider platform. It does not have the building. Before adding Microsoft, Apple, or Zoom: ~10 weeks of disciplined refactoring + operational
hardening, after which each new provider costs 4–14 weeks. If you skip the refactor, each new provider costs ~50% more and the system accumulates parallel Google-shaped islands that diverge. The framework
parts (sync jobs, projections, fencing) are good; the auth/OAuth/webhook/conferencing surface is single-vendor-coupled and must be reshaped. Zoom — your most monetizable integration — is the simplest and
the least supported by current abstractions because conferencing is not modeled at all.

⏺ Audit delivered. Headline: the codebase has the vocabulary of a multi-provider design (CalendarProvider / CalendarProviderClient interfaces, CalendarProviderType enum with GOOGLE, MICROSOFT,
calendar.provider.mode config) but the implementation is single-vendor. TokenRefresher and CalendarOAuthService are typed to GoogleApiClient; webhook ingestion has one public method ingestGoogle() with 9
hardcoded "google" strings; the controller URLs are /google/connect, /google/callback, /webhooks/google; the status endpoint returns {"google": "CONNECTED"} as a shape; BookingRepository has 4 hardcoded
'google' literals in SQL; PublicBookingService has String provider = "google"; as a local; conferencing is not abstracted at all (Google Meet is wired into HttpGoogleApiClient as hangoutsMeet); and beans
use @ConditionalOnProperty(... matchIfMissing = true) so the app starts with exactly one provider bean. The sync framework (jobs, projections, fencing, reconciler) is genuinely provider-agnostic and
survives. Webhook subscription renewal cron is missing — Google watch channels silently die after ~7 days today. Realistic plan: ~10 weeks of refactor + operational hardening before any new provider lands;
then Zoom ~4w (best ROI), Outlook ~8w, CalDAV ~14w.

