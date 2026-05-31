## 1. High-Level Overview (User Perspective)

BunnyCal is a scheduling platform with two main personas:

- Host: sets availability, connects Google Calendar, receives bookings.
- Client: views open time slots and requests a booking.

When a host clicks Connect Google Calendar, the app asks Google for permission, then stores a secure long-lived credential (encrypted refresh token). Later, when calendar API calls are needed, the app gets
short-lived access tokens on demand.

———

## 2. Key User Flows (Derived from Code)

### A) Login flow (OAuth + JWT + refresh)

1. User starts OAuth login via Spring Security /oauth2/**.
2. CustomOAuth2UserService normalizes provider attributes (provider, providerUserId, email, name).
3. OAuth2AuthenticationSuccessHandler resolves/creates local user identity.
4. Backend returns:
    - JWT access token (JwtTokenProvider)
    - Rotating refresh token (RefreshTokenService)
5. Later, /auth/refresh validates refresh token and rotates it, issuing a new JWT.

### B) Connect calendar flow (/integrations/calendar/google/*)

1. Authenticated user calls GET /integrations/calendar/google/connect.
2. CalendarOAuthService.buildGoogleConnectUrl() builds Google consent URL with client_id, scopes, redirect URI, state.
3. User consents at Google; Google redirects to GET /integrations/calendar/google/callback?code&state.
4. handleGoogleCallback():
    - validates state
    - exchanges code for tokens
    - fetches provider user ID
    - stores encrypted refresh token (or reuses existing if refresh token not returned)
    - stores connection metadata (status, scopes, token expiry metadata)
5. User is redirected to frontend success/error page.

### C) Booking creation flow (POST /api/bookings)

1. Request must include Idempotency-Key.
2. BookingController hashes request payload for idempotency matching.
3. IdempotencyService attempts insert-first lock row in idempotency_keys.
4. If first request:
    - calls BookingService.createBooking()
    - booking is persisted
    - outbox event is published in same transaction (OutboxPublisher)
    - idempotency row finalized with cached response
5. If duplicate request:
    - same hash + terminal status => replay response
    - hash mismatch => error
    - still processing => poll then return in-progress/replay result

### D) Availability calculation flow

1. Client calls GET /api/users/{userId}/event-types/{eventTypeId}/slots?date=....
2. SlotService loads host + event type.
3. Reads slot cache version; checks cache.
4. On miss, computes from:
    - availability rules
    - override for date
    - existing overlapping bookings
    - DB clock (SELECT now())
5. Returns slots with deterministic slot IDs.
6. Note from current code: calendarBusy is currently List.of() in slot generation path.

### E) Background processing flow (outbox + sync)

1. Booking writes outbox_events.
2. OutboxWorker polls every second:
    - atomically claims rows (FOR UPDATE SKIP LOCKED)
    - dispatches event (current default dispatcher logs only)
    - marks PROCESSED or RETRYING/FAILED
3. OutboxReaper runs every 30s to recover stuck PROCESSING rows.
4. Sync pipeline also exists:
    - OutboxProcessor converts booking outbox events to calendar_sync_jobs
    - BookingSyncWorker processes sync jobs and calls CalendarService.

———

## 3. Behind the Scenes (Technical, Simple)

- JWT strategy: stateless auth via bearer token; JWT subject is user UUID.
- Refresh token strategy (auth): refresh tokens are validated and rotated.
- Google calendar OAuth: separate from login OAuth; uses google.oauth.* config and explicit connect/callback endpoints.
- No access-token persistence for calendar: connection stores encrypted refresh token and token-expiry metadata; access token is fetched at use time via refresh flow.
- Refresh token encryption: AesGcmTokenCipher (AES-GCM with IV + versioned ciphertext prefix).
- Connection lifecycle: ACTIVE, ERROR, REVOKED, DISCONNECTED with last_error_code + last_error_at.
- Outbox reliability model:
    - transactional write with business data
    - claim with row locking
    - retry/backoff
    - stuck-row reaper
    - processed-events guard to avoid duplicate dispatch

———

## 4. Architecture Overview

- Identity/Auth module
    - OAuth user normalization
    - JWT generation/validation
    - refresh token lifecycle
    - security filter chain
- Calendar integration module
    - Google connect/callback endpoints
    - OAuth state validation
    - encrypted token storage
    - token refresh and provider operations
- Booking module
    - booking creation + constraints
    - idempotency replay logic
    - booking state transitions
    - outbox event publishing
- Worker/Async module
    - outbox poller + retry + reaper
    - outbox-to-sync-job router
    - sync worker for calendar side effects
- Data layer
    - PostgreSQL with Flyway migrations
    - hardened calendar_connections
    - partitioned bookings with overlap constraints
    - outbox + processed-events tables

———

## 5. Diagrams

### A) System Architecture

graph TD
U[Host/Client] --> FE[Frontend]
FE --> API[Spring Boot API]

      API --> AUTH[Auth Module]
      API --> AVAIL[Availability Module]
      API --> BOOK[Booking Module]
      API --> CAL[Calendar Module]
      API --> OUT[Outbox/Sync Workers]

      AUTH --> GLOGIN[Google OAuth Login]
      CAL --> GOAUTH[Google OAuth Calendar]
      CAL --> GCAL[Google Calendar API]

      API --> DB[(PostgreSQL)]
      API --> REDIS[(Redis Slot Cache)]

### B) OAuth Flow (Calendar Connect)

sequenceDiagram
participant H as Host
participant C as CalendarIntegrationController
participant S as CalendarOAuthService
participant G as Google OAuth

      H->>C: GET /integrations/calendar/google/connect
      C->>S: buildGoogleConnectUrl(userId)
      S-->>H: redirectUrl

      H->>G: Open consent URL
      G->>C: GET /integrations/calendar/google/callback?code&state
      C->>S: handleGoogleCallback(code,state)
      S->>G: exchange code for tokens
      S->>G: fetch provider user id
      S->>S: encrypt refresh token
      S->>DB: save/update calendar_connections
      C-->>H: 302 success/error frontend URL

### C) Booking Flow

sequenceDiagram
participant Client
participant BC as BookingController
participant IDS as IdempotencyService
participant BS as BookingService
participant OP as OutboxPublisher
participant DB as PostgreSQL

      Client->>BC: POST /api/bookings + Idempotency-Key
      BC->>IDS: execute(key, requestHash, work)
      IDS->>DB: try insert idempotency row

      alt fresh request
        IDS->>BS: createBooking()
        BS->>DB: save booking
        BS->>OP: publish outbox event
        OP->>DB: save outbox_events row
        IDS->>DB: finalize idempotency as COMPLETED
        IDS-->>Client: 201 booking response
      else duplicate request
        IDS->>DB: load existing key row
        IDS-->>Client: replay / mismatch / in-progress
      end

### D) Token Refresh Flow

sequenceDiagram
participant Prov as GoogleCalendarProvider/CalendarService
participant TR as TokenRefresher
participant DB as calendar_connections
participant G as Google Token API
participant GA as Google Calendar API

      Prov->>TR: executeWithValidToken(connectionId, operation)
      TR->>DB: load connection + status + encrypted refresh token
      TR->>TR: decrypt refresh token
      TR->>G: refreshAccessToken(refresh_token)
      G-->>TR: access_token + expires_at
      TR->>DB: update last_token_expires_at, status/error fields
      TR->>GA: call API with access token
      GA-->>Prov: result

### E) Outbox Processing Flow

sequenceDiagram
participant W as OutboxWorker
participant DB as outbox_events
participant D as OutboxEventDispatcher
participant R as OutboxReaper

      loop every 1s
        W->>DB: claimBatch(now,batch) with SKIP LOCKED
        W->>DB: load claimed row
        W->>D: dispatch(event)
        alt success
          W->>DB: mark PROCESSED
        else failure
          W->>DB: mark RETRYING/FAILED + attempts + next_attempt_at
        end
      end

      loop every 30s
        R->>DB: recover stuck PROCESSING to PENDING
        R->>DB: fail exhausted rows
      end

———

## 6. Key Technical Decisions (From Code)

- Access tokens not stored: reduces persistence of short-lived bearer credentials; token is fetched when needed.
- Refresh tokens encrypted: refresh token is high-value secret; AES-GCM protects at rest.
- Scopes as array (TEXT[]): enables DB-native querying (ANY(scopes)) and indexing (GIN).
- Unique user/provider constraint: enforces one canonical calendar connection per provider per user.
- Lifecycle tracking fields: status, last_error_code, last_error_at, last_synced_at improve observability and recovery logic.
- Outbox pattern: ensures async side effects are tied to successful DB commits and recoverable with retries.

———

## 7. Real End-to-End Scenario Walkthrough

1. Host logs in with Google OAuth login and receives JWT + refresh token.
2. Host calls /integrations/calendar/google/connect, approves calendar access at Google.
3. Callback stores encrypted calendar refresh token and marks connection ACTIVE.
4. Client requests slots for a date; backend computes from availability + existing bookings.
5. Client submits booking with Idempotency-Key.
6. Backend saves booking and outbox event atomically.
7. Worker picks outbox event and processes async pipeline.
8. When a calendar API call is needed, TokenRefresher decrypts refresh token, gets short-lived access token, executes API call.
9. If token refresh fails (e.g., revoked grant), connection state is updated (ERROR/REVOKED) with error metadata.

———

## 8. Current Capabilities vs Future Work

### ✅ Implemented (in code now)

- JWT auth filter and refresh endpoint.
- OAuth login normalization/success handling.
- Google calendar connect/callback endpoints.
- Encrypted refresh-token storage for calendar.
- Non-persistent access-token model for calendar integration.
- Booking + idempotency + transactional outbox.
- Outbox worker retry/reaper mechanics.
- Hardened calendar_connections schema (unique index, array scopes, lifecycle fields).

### 🔜 Missing / next logical steps

1. Integrate real external dispatcher for outbox default path (current default is logging dispatcher).
2. Ensure full calendar busy-time integration in slot calculation path (currently empty list in SlotService compute path).
3. Expand last_synced_at usage in sync job completion/update paths.
4. Tighten secret defaults in config for production deployments.
