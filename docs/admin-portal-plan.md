# BunnyCal Admin Portal — Technical Implementation Plan

> **Status:** Architecture & planning only. No production code in this document.
> **Date:** 2026-06-30
> **Scope:** Internal Admin Portal (`https://admin.bunnycal.io`) on the shared Spring Boot backend.
>
> **Production domains:**
> - Customer: `https://bunnycal.io`
> - Admin: `https://admin.bunnycal.io`
> - Backend: `https://api.bunnycal.io`

> **Locked decisions (2026-06-30):**
> 1. **Single frontend monorepo, two apps — NOT a second repo.** Keep `BunnyCal-api` and `BunnyCal-web`. Add workspace support to `BunnyCal-web` and extract shared `packages/ui/` + `packages/api-client/`, then add the new `apps/admin/` beside the existing customer app. **The customer app stays at the repo root** — it is *not* relocated to enable the portal; the `apps/customer/` move is an optional post-launch cleanup, kept off the critical path (Plan §2.1–§2.3). Same React version, Tailwind, design system, API client, build pipeline, and CI. A separate *deployment* (`admin.bunnycal.io`) does not require a separate *Git repository* — for a solo founder, another repo is just more maintenance. (Plan §2.)
> 2. **Phase 0 enforces `ADMIN`/`SUPER_ADMIN` only.** `SUPPORT`/`FINANCE`/`OPERATIONS` are reserved in the `AdminRole` enum and tightened per-endpoint via `@PreAuthorize` as later modules land. (Plan §4.1.)

---

## 0. Grounding: what already exists in the codebase

This plan is deliberately conservative because a surprising amount of the backend domain is already present. Before proposing anything new, here is what was verified against the current code:

| Area | Current state | Implication for the plan |
|---|---|---|
| **Spring Security RBAC** | **Does not exist.** No `@PreAuthorize`, `hasRole`, `GrantedAuthority`, or `UserDetails` anywhere in `src/main/java`. | The phrase "RBAC already exists" refers to ownership checks at the application layer, not Spring method security. Admin authorization is **net-new** and is the single most important backend change. |
| **JWT** | `JwtTokenProvider` mints tokens with only `sub` (userId), `email`, `type=access`. `JwtAuthenticationFilter` builds the principal as a **bare userId UUID with `Collections.emptyList()` authorities** (line 134 literally says `// replace later with roles if needed`). | Roles must be added as a JWT claim **and** mapped to Spring authorities in the filter. |
| **Existing `/api/admin/**`** | `AdminBillingController` and `SyncAdminController` exist, gated only by `@ConditionalOnProperty` + a manual `requireAuth()` that accepts *any* authenticated user. | The pattern (URL namespace + config flag) is the right precedent; the authorization is the gap. These controllers get retrofitted with real role checks. |
| **`User` entity** | No role column. `UserStatus` enum = `ACTIVE / INACTIVE / DELETED`. | Roles live in a **separate join table** (not a column on `users`) to support multiple roles per admin and future role expansion. Suspend/unsuspend reuses `UserStatus`. |
| **Plans** (billing catalog) | `SubscriptionPlan` table already stores `code, name, description, provider_price_id, amount_minor, currency, billing_interval, trial_days, active, sort_order`. `Subscription.planId` already FKs it. | **No new table needed** — extend `subscription_plans` with `provider_product_id` and `visibility`. This is the cleanest win in the whole plan. Surfaced in the UI as **Plans** (§6.4), the standard SaaS term. |
| **Audit** | `PaymentAuditLog` is append-only with `actor, entity_type, entity_id, action, before_json, after_json, created_at` (JSONB). | Excellent template. The general admin audit log is a **new sibling table** (`admin_audit_logs`) that adds `admin_id`, `ip_address`, `user_agent` and a generic target — rather than overloading the financial one. |
| **Webhooks** | `WebhookEvent` table: `provider, provider_event_id, type, payload (JSONB), status (RECEIVED/…), error, received_at, processed_at`. | The Webhooks module is a **read-only viewer + retry trigger** over an existing table. No schema change beyond an optional `attempt_count`/`next_retry_at` if not present. |
| **Feature flags** | **Do not exist as data.** `PlanCatalog` maps `PlanTier → Feature/LimitKey` in code only. | Per-user and global feature flags are **genuinely new** (table + service + evaluation hook). The existing `Feature` enum becomes the registry of flag keys. |
| **Promotions** | `Coupon`, `PromoCode`, `ManualDiscount`, `PromotionService`, `RefundService` all exist; `AdminBillingController` already creates coupons/promo-codes/refunds. | Promotions module is mostly **UI over existing services** + read/list/disable endpoints. |
| **Frontend** | React 18, Vite, TanStack Query v5, React Router v6, Tailwind. Design system in `src/ui` (`controls/` + `layout/` incl. `AppShell`, `Sidebar`, `PageShell`, `Dialog`, `Badge`, `Button`, `Input`, etc.). Strict layering: `ui/*` imports nothing from `domain/services/state/features/pages`. | Because `ui/*` is already dependency-free, it can be lifted into a shared package (or copied) for the admin app with near-zero refactor. |
| **OAuth flow** | `OAuth2AuthenticationSuccessHandler` redirects to a **single hardcoded** `app.public-base-url`. | The admin app needs a way to redirect back to `admin.bunnycal.io` after Google login — handled via the OAuth `state`/`redirect_uri` resolution described in §4. |
| **Migrations** | Flyway, sequential `V<n>_0__name.sql`. Latest = `V105_0`. | New migrations start at `V106_0`. |
| **API envelope** | `ApiResponse<T>` = `{ success, data, error{code,message} }`. | Admin endpoints return the same envelope for frontend consistency. |

**Net takeaway:** roughly 60% of the backend domain the admin portal needs already exists. The real new work is (1) the authorization model, (2) feature flags, (3) the admin audit log, (4) the dashboard/revenue/analytics aggregation queries, (5) the new operational surfaces (Operations dashboard, System Jobs, Announcements, dynamic Settings), and (6) a new React app added to the existing frontend monorepo.

---

## 1. Overall Architecture

```
                ┌──────────────────────────────────┐
                │   Customer React App             │
                │   https://bunnycal.io            │
                │   (BunnyCal-web, root — unchanged)│
                └──────────────┬───────────────────┘
                               │  JWT (role: USER)
                               ▼
        ┌──────────────────────────────────────────────┐
        │            Spring Boot API                     │
        │            https://api.bunnycal.io             │
        │   ┌────────────────────────────────────────┐   │
        │   │  Existing modules (auth, billing,      │   │
        │   │  booking, calendar, …)                 │   │
        │   ├────────────────────────────────────────┤   │
        │   │  NEW: io.bunnycal.admin.*              │   │
        │   │  /api/admin/** behind ADMIN roles      │   │
        │   └────────────────────────────────────────┘   │
        └──────────────────────────────────────────────┘
                               ▲  JWT (role: ADMIN/…)
                               │
                ┌──────────────┴───────────────────┐
                │   Admin React App                │
                │   https://admin.bunnycal.io      │
                │   (BunnyCal-web/apps/admin)      │
                └──────────────────────────────────┘
                               │
                               ▼
                ┌─────────────────────────────┐
                │   PostgreSQL (shared)       │
                └─────────────────────────────┘
```

**Principles**
1. **One backend, one database.** The admin app is purely another API client. No duplicated services, repositories, or business logic.
2. **Hard separation at the URL + role boundary.** All admin functionality is namespaced under `/api/admin/**` and requires an admin role. The customer app's bundle contains zero admin code; the admin app's bundle contains zero customer-booking code.
3. **Admin app is independent but not from-scratch.** It reuses the design system and API-client primitives so we don't maintain two divergent UI kits.
4. **Defense in depth.** Three independent gates: (a) the JWT must carry an admin role, (b) Spring method/URL security enforces the role on every `/api/admin/**` call, (c) CORS only permits the admin origin to send credentialed admin requests.

---

## 2. Repository / Deployment Topology

**Two Git repositories, two frontend apps inside one of them.** We do **not** create a third repository. A separate *deployment* does not require a separate *repository* — the two React apps share the same React version, Tailwind, design system, API client, build pipeline, and CI, so a second repo would only add maintenance overhead (especially for a solo founder).

| Deployable | Source | Host | Build |
|---|---|---|---|
| Backend | `BunnyCal-api` (unchanged) | `https://api.bunnycal.io` | Gradle / Spring Boot |
| Customer web | `BunnyCal-web` (root, unchanged) | `https://bunnycal.io` | Vite |
| **Admin web (new)** | `BunnyCal-web/apps/admin` | `https://admin.bunnycal.io` | Vite |

### 2.1 Guiding principle: don't refactor what the feature doesn't require

**Never perform a large structural refactor unless it directly enables a feature.** The admin portal needs three things from the frontend: (1) workspace support, (2) a shared `@bunnycal/ui` package, and (3) a shared `@bunnycal/api-client` package. It does **not** require relocating the existing, production customer app into an `apps/customer/` directory. Moving the customer app is an *organizational* improvement, not a *functional* prerequisite — so it waits until after the admin portal is live and stable, removing the riskiest step from the critical path without sacrificing any long-term architecture.

### 2.2 Recommended (primary): customer app stays put; add packages + admin app beside it

Introduce workspace support **without moving the customer app**. The existing `src/` stays exactly where it is at the repo root; only the genuinely shared code is extracted into packages, and the new admin app is added alongside.

```
BunnyCal-web/
  package.json            # becomes the workspaces root (adds "workspaces": ["packages/*", "apps/*"])
  src/                    # ← customer app: UNCHANGED location, unchanged behavior
  apps/
    admin/                # ← NEW Vite app only
      src/
      vite.config.ts
      tailwind.config.js  # extends the shared preset
  packages/
    ui/                   # ← extracted from src/ui  → @bunnycal/ui
    api-client/           # ← extracted apiClient/authenticatedApiClient/authEvents/queryClient → @bunnycal/api-client
    tailwind-preset/      # ← shared Tailwind theme (colors, spacing, tokens)
```

What this touches in the customer app is **minimal and reversible**: its `package.json` participates in the workspace, and its imports of `ui/*` and the api-client primitives repoint to `@bunnycal/ui` / `@bunnycal/api-client`. Because `src/ui` already forbids imports from `domain/services/state/features/pages` (enforced layering) and the api-client primitives are self-contained, the extraction is mechanical and the customer app's runtime behavior is unchanged. Its build/deploy at `bunnycal.io` continues from the repo root.

> **Optional later cleanup (not required for the admin portal):** once the portal is live and stable, move `src/` → `apps/customer/src/` so both apps sit symmetrically under `apps/`. This is a pure organizational tidy-up, scheduled on its own, off the admin-portal critical path. Until then, the asymmetry (customer at root, admin under `apps/`) is a deliberate, acceptable trade for lower risk.

### 2.3 Fallback (only if you prefer symmetry up front): move the customer app in Phase 0

If you'd rather have both apps under `apps/` from day one, do the relocation as part of Phase 0: `src/` → `apps/customer/src/`, repoint path aliases (`@/…`) in each app's `vite.config.ts`/`tsconfig`, and **verify the customer app builds and behaves identically before scaffolding admin**. This is still a move, not a rewrite — but it puts a refactor of the production app on the critical path, which §10 flags as the single riskiest step. The recommended approach in §2.2 avoids that; choose this only if the symmetry is worth the added risk to you.

### 2.4 Independence regardless of layout

Each app keeps its **own** `vite.config.ts`, router, env (`VITE_API_BASE_URL`), and deployment target. They build and deploy independently (admin → `admin.bunnycal.io`, customer → `bunnycal.io`) — the shared packages are just build-time dependencies. CI runs one install at the workspace root, then builds the apps as separate jobs/artifacts.

---

## 3. Database Changes

All changes are additive Flyway migrations starting at `V106_0`. No destructive changes to existing tables.

### 3.1 Roles (new tables) — `V106_0__admin_roles.sql`

A separate join table rather than a column on `users`, so an admin can hold multiple roles and the role set can grow without schema churn.

```
admin_roles
  id            UUID PK
  user_id       UUID NOT NULL  → users(id)
  role          VARCHAR(32) NOT NULL   -- ADMIN, SUPER_ADMIN, SUPPORT, FINANCE, OPERATIONS
  granted_by    UUID            → users(id)  (nullable for the bootstrap super-admin)
  granted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  revoked_at    TIMESTAMPTZ              -- null = active
  UNIQUE (user_id, role) WHERE revoked_at IS NULL   -- partial unique index
```

- `role` is a `VARCHAR` mapped to a Java enum (`AdminRole`), not a DB enum — consistent with how `UserStatus`/`SubscriptionStatus` are stored as `EnumType.STRING`.
- The very first `SUPER_ADMIN` is seeded by a one-off migration or a guarded bootstrap (env var `admin.bootstrap.email`) so we are never locked out.
- Regular customers simply have **no rows** here → treated as role `USER`.

### 3.2 Admin audit log (new table) — `V107_0__admin_audit_logs.sql`

Mirrors `PaymentAuditLog` (append-only, JSONB before/after) but adds the admin-specific fields the requirements call for.

```
admin_audit_logs
  id            UUID PK
  admin_id      UUID NOT NULL      → users(id)   -- who
  admin_email   VARCHAR(255)                     -- denormalized snapshot
  action        VARCHAR(64) NOT NULL             -- e.g. USER_SUSPEND, GRANT_PRO, REFUND_ISSUE
  target_type   VARCHAR(64) NOT NULL             -- USER, SUBSCRIPTION, PLAN, COUPON, FLAG …
  target_id     UUID
  reason        TEXT                             -- admin's free-text justification (see below)
  before_json   JSONB
  after_json    JSONB
  ip_address    VARCHAR(64)
  user_agent    VARCHAR(512)
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX (target_type, target_id)
  INDEX (admin_id, created_at)
  INDEX (action, created_at)
```

`reason` captures *why* an action was taken — e.g. action `GRANT_PRO`, reason `"Lifetime reward"`. It becomes invaluable months later when reviewing why a user has a non-standard state. Every destructive/grant action in the UI routes through `ConfirmActionDialog`, which collects this reason and sends it with the request.

Append-only by convention (no update/delete paths in code). The `PaymentAuditLog` continues to own *financial* actions; this owns *all* admin actions (which includes a row pointing at the financial event for cross-reference).

### 3.3 Billing catalog (extend existing) — `V108_0__plan_catalog_fields.sql`

The existing `subscription_plans` table is already the catalog. Add only what's missing:

```
ALTER TABLE subscription_plans
  ADD COLUMN provider_product_id VARCHAR(255),   -- Dodo/Stripe product id (price id already exists)
  ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';  -- PUBLIC | UNLISTED | INTERNAL
```

- `active` already exists (active status).
- `provider_price_id`, `currency`, `billing_interval`, `trial_days` already exist.
- New `Subscription` rows continue to reference `plan_id`; the manual "edit IDs in the DB" workflow is replaced by the admin Billing Catalog screen writing these columns. **No new table, no data migration of existing subscriptions.**

### 3.4 Feature flags (new tables) — `V109_0__feature_flags.sql`

Two tables: a registry of flag definitions, and per-target overrides.

```
feature_flags
  key           VARCHAR(64) PK            -- matches the existing Feature enum keys where applicable
  description   TEXT
  default_value BOOLEAN NOT NULL DEFAULT false   -- global default when no override
  enabled       BOOLEAN NOT NULL DEFAULT true     -- master kill switch
  created_at / updated_at  TIMESTAMPTZ

feature_flag_overrides
  id            UUID PK
  flag_key      VARCHAR(64) NOT NULL → feature_flags(key)
  user_id       UUID                  → users(id)    -- null = global override
  value         BOOLEAN NOT NULL
  reason        TEXT
  created_by    UUID → users(id)
  created_at    TIMESTAMPTZ
  UNIQUE (flag_key, user_id)   -- (with null user_id treated as the single global override)
```

Evaluation precedence: per-user override → global override → `feature_flags.default_value` → (fallback) `PlanCatalog` entitlement. This lets flags **layer on top of** the existing plan-tier entitlement system without replacing it.

### 3.5 Optional: webhook retry metadata

If `webhook_events` lacks retry bookkeeping, add `attempt_count INT DEFAULT 0` and `next_retry_at TIMESTAMPTZ` in `V110_0`. The Webhooks module otherwise needs **no** schema change.

### 3.6 Announcements (new table) — `V111_0__announcements.sql`

Lets an admin publish a banner/notice (maintenance, feature rollout, beta) **without a deploy**.

```
announcements
  id            UUID PK
  title         VARCHAR(160)
  body          TEXT NOT NULL
  level         VARCHAR(16) NOT NULL DEFAULT 'INFO'   -- INFO | WARNING | CRITICAL
  audience      VARCHAR(16) NOT NULL DEFAULT 'ALL'    -- ALL | FREE | PAID  (who sees it)
  starts_at     TIMESTAMPTZ
  ends_at       TIMESTAMPTZ
  active        BOOLEAN NOT NULL DEFAULT true
  created_by    UUID → users(id)
  created_at / updated_at  TIMESTAMPTZ
  INDEX (active, starts_at, ends_at)
```

The customer app fetches active announcements via a **public** read endpoint (`GET /api/announcements/active`) and renders a banner. The admin app does full CRUD. This is the only admin-managed table the customer app reads from directly.

### 3.7 Dynamic settings (new table) — `V112_0__app_settings.sql`

A typed key/value store so configuration that changes occasionally (email copy toggles, feature rollout switches, provider display options) doesn't require a redeploy or an env-var change. **Secrets stay in env/secret-manager** — this table holds *non-secret operational* config only.

```
app_settings
  key           VARCHAR(96) PK            -- e.g. "emails.welcome.enabled", "billing.dodo.display_mode"
  value         JSONB NOT NULL            -- typed value (bool/number/string/object)
  category      VARCHAR(32) NOT NULL      -- GENERAL | BILLING | EMAILS | OAUTH | DODO | GOOGLE | MICROSOFT | S3
  description   TEXT
  is_secret     BOOLEAN NOT NULL DEFAULT false   -- secret keys are read-only in UI; value is masked
  updated_by    UUID → users(id)
  updated_at    TIMESTAMPTZ
```

A `SettingsService` reads with a short cache and falls back to the existing `@Value`/`*Properties` defaults when a key is absent — so adoption is incremental (move values into the table one at a time; nothing breaks if a key isn't there yet). Every write is audited.

---

## 4. Security Model & Authentication

This is the core new backend capability. Goal: admins log in with Google OAuth (same flow), the backend issues a JWT that carries their roles, and `/api/admin/**` is enforced by Spring Security. Customer tokens must be structurally incapable of reaching admin endpoints.

### 4.1 Role model

`enum AdminRole { ADMIN, SUPER_ADMIN, SUPPORT, FINANCE, OPERATIONS }` plus the implicit `USER`.

Initial enforcement granularity (coarse, expand later):

| Role | Access |
|---|---|
| `SUPER_ADMIN` | Everything, including granting/revoking admin roles. |
| `ADMIN` | Everything except role management. |
| `SUPPORT` | User management (read + suspend/reset onboarding/extend trial), bookings, feature flags read. No refunds, no catalog edits. |
| `FINANCE` | Revenue, billing catalog, subscriptions, refunds, promotions. |
| `OPERATIONS` | Webhooks, system health, sync dead-letters. |

These map to method-level rules; in Phase 1 we can ship with just `ADMIN`/`SUPER_ADMIN` enforced and the others reserved, then tighten per-endpoint as modules land.

### 4.2 JWT changes (`JwtTokenProvider`)

Add a `roles` claim to the access token:

```
.claim("roles", ["ADMIN"])     // empty/absent for normal users
```

Roles are loaded from `admin_roles` at token-mint time (in `OAuth2AuthenticationSuccessHandler` and the refresh path). Because admin sessions are sensitive, **shorten admin access-token TTL** (e.g. mint a short-lived token when roles are present) and rely on refresh.

### 4.3 Filter changes (`JwtAuthenticationFilter`)

Replace `Collections.emptyList()` with authorities derived from the `roles` claim:

```
roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)))
```

The principal stays the userId UUID (no breaking change to existing controllers that call `auth.getPrincipal().toString()`).

### 4.4 Security configuration (`SecurityConfig`)

- Enable method security: `@EnableMethodSecurity`.
- Add an explicit URL rule **before** the generic `/api/**` rule:

```
.requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN", "SUPPORT", "FINANCE", "OPERATIONS")
```

- Per-endpoint refinement via `@PreAuthorize("hasRole('FINANCE')")` on controllers/methods.
- **CORS:** register a dedicated CORS config for `/api/admin/**` that allows **only** `https://admin.bunnycal.io` with credentials, separate from the customer-origin rule. Combined with the role check, a customer token from `https://bunnycal.io` cannot reach admin APIs even if it tried (it has no `ROLE_ADMIN`, and the origin isn't allowed).

Result — three independent gates:
1. Token must contain an admin role (else no `ROLE_*` authority).
2. `/api/admin/**` requires an admin role at the URL matcher + method level.
3. Admin CORS origin is restricted to `admin.bunnycal.io`.

### 4.5 OAuth redirect for the admin app

`OAuth2AuthenticationSuccessHandler` currently redirects to one hardcoded `app.public-base-url`. To support a second frontend:

- Resolve the post-login redirect target from the OAuth `state` parameter / a `redirect_origin` carried through `CustomOAuth2AuthorizationRequestResolver`, **validated against an allowlist** (`bunnycal.io`, `admin.bunnycal.io`). Never reflect an arbitrary origin (open-redirect risk).
- After role-bearing login, redirect to `admin.bunnycal.io/...`. The admin SPA then exchanges/holds the token exactly as the customer app does.
- **Login does not gate on role.** Any Google user can complete OAuth; the admin app simply shows "not authorized" if the resulting token carries no admin role, and admin APIs return 403. (Avoids leaking which emails are admins.)

### 4.6 Retrofit existing admin controllers

`AdminBillingController` and `SyncAdminController` currently authorize *any* authenticated user behind a config flag. Once roles exist, replace their manual `requireAuth()` with `@PreAuthorize` and drop/retain the `@ConditionalOnProperty` flag as a kill switch. This closes a real current gap, not just new surface.

---

## 5. Backend Structure (new module)

New top-level package `io.bunnycal.admin`, organized by admin module, **delegating to existing services** wherever possible.

```
io.bunnycal.admin
  ├─ security/        AdminRole, AdminRoleEntity, AdminRoleRepository, AdminRoleService
  ├─ audit/           AdminAuditLog (entity), AdminAuditLogRepository, AdminAuditService,
  │                   @Audited aspect or explicit audit() calls
  ├─ search/          AdminSearchController, GlobalSearchService (fans out across users,
  │                   subscriptions, invoices, bookings, webhooks → unified result) ← "Search Everything"
  ├─ dashboard/       AdminDashboardController, DashboardMetricsService (read-model queries)
  ├─ operations/      AdminOperationsController, OperationsService (action-needed counts:
  │                   failed webhooks/payments, pending refunds, subs needing sync) ← daily landing page
  ├─ users/           AdminUserController, AdminUserService (wraps UserRepository,
  │                   SubscriptionService, InvoiceService, BookingRepository, EntitlementService,
  │                   calendar/conferencing repositories for the integrations tabs)
  ├─ subscriptions/   AdminSubscriptionController, AdminSubscriptionService
  │                   (wraps SubscriptionService/SubscriptionStateService + PaymentProvider for sync)
  ├─ plans/           AdminPlanController, PlanCatalogService (over SubscriptionPlanRepository) ← "Plans"
  ├─ revenue/         AdminRevenueController, RevenueReportService (aggregation over invoices/refunds)
  ├─ analytics/       AdminAnalyticsController, ProductAnalyticsService (product metrics:
  │                   signups, bookings created/cancelled, conversion, popular event, timezones, countries)
  ├─ promotions/      AdminPromotionController (wraps PromotionService/RefundService)
  ├─ flags/           FeatureFlag(s) entity+repo, FeatureFlagService, AdminFlagController
  ├─ webhooks/        AdminWebhookController (over WebhookEventRepository + WebhookIngestionService retry)
  ├─ jobs/            AdminJobsController, SystemJobsService (over outbox, email queue, sync queue,
  │                   dead letters, retries — reuses SyncAdminService/outbox repositories) ← "System Jobs"
  ├─ announcements/   Announcement entity+repo, AnnouncementService, AdminAnnouncementController
  │                   + public AnnouncementController (GET /api/announcements/active)
  ├─ settings/        AppSetting entity+repo, SettingsService, AdminSettingsController
  └─ health/          AdminHealthController (aggregates Actuator + provider pings)
```

### 5.1 Reuse map (avoid duplication)

| Admin need | Reuses existing |
|---|---|
| User profile/subscription/invoices | `UserRepository`, `SubscriptionService`, `InvoiceService`, `SubscriptionInvoiceRepository` |
| Grant/remove Pro, extend trial, lifetime | `SubscriptionService` / `SubscriptionStateService` (add admin-intent methods if missing) |
| Suspend/unsuspend | `User.status` (`UserStatus`) via `UserRepository` |
| Refunds | `RefundService` (already used by `AdminBillingController`) |
| Coupons / promo codes / manual discounts | `PromotionService` |
| Subscription sync/refresh | `PaymentProvider` (`DodoProvider`/`StripeProvider`) + webhook replay |
| Entitlements view | `EntitlementService` |
| Webhook list/retry | `WebhookEventRepository`, `WebhookIngestionService` |
| Bookings view | booking module repositories |

New services are thin orchestrators that add: role checks, audit logging, and admin-only read aggregations. **No business logic is reimplemented.**

### 5.2 Audit logging requirement

Every state-changing admin endpoint records an `admin_audit_logs` row capturing admin id/email, action, target, before/after snapshots, IP, user-agent. Implement via either:
- An `@Audited` annotation + AOP aspect that serializes before/after around the call, or
- Explicit `adminAuditService.record(...)` calls in each admin service method (simpler, more precise for before/after of partial updates).

Recommended: explicit calls in services for write actions (clear before/after), backed by a small helper. Reads are not audited (volume).

### 5.3 DTOs

Per module, request/response records under `io.bunnycal.admin.<module>.dto`, returning the existing `ApiResponse<T>` envelope. Paginated list endpoints return a standard `PageResponse<T>` (page, size, total, items) — define once in `admin.common`.

---

## 6. API Design (per module)

All under `/api/admin`, all returning `ApiResponse<…>`, all role-gated. List endpoints accept `?page&size&sort&q&...filters`.

**Search Everything** — `GET /search?q=` — one query, fanned out across users, subscriptions, invoices, bookings, and webhooks; returns a grouped result (`{ users:[…], subscriptions:[…], invoices:[…], bookings:[…], webhooks:[…] }`). Powers both the top-bar global search and the ⌘K command palette.

**Operations** (daily landing page) — `GET /operations/summary` — action-needed counts: failed webhooks, failed payments, pending refunds, subscriptions requiring sync, failed Dodo webhooks, recent support actions. Each item links into its detail screen.

**Dashboard** — `GET /dashboard/metrics` (totals + MRR + failed payments + refunds + conversion), `GET /dashboard/growth?range=` (time series).

**Analytics** (product, not revenue) — `GET /analytics/summary?from=&to=` (new users, bookings created/cancelled, conversion, avg booking duration), `GET /analytics/top-events`, `GET /analytics/timezones`, `GET /analytics/countries`.

**Users** — `GET /users?q=&status=&plan=` (search by email/userId/subscriptionId/dodoCustomerId), `GET /users/{id}` (profile + subscription + entitlements summary), `GET /users/{id}/authentication` (linked identities/providers), `GET /users/{id}/invoices`, `GET /users/{id}/bookings`, `GET /users/{id}/calendars` (connected calendars), `GET /users/{id}/conferencing` (Zoom/Teams connections), `GET /users/{id}/flags`, `GET /users/{id}/audit`. Actions (each accepts a `reason`): `POST /users/{id}/suspend`, `/unsuspend`, `/reset-onboarding`, `/grant-pro`, `/remove-pro`, `/extend-trial`, `/grant-lifetime`.

**Subscriptions** — `GET /subscriptions/{id}` (local + provider snapshot), `POST /subscriptions/{id}/refresh`, `/sync`, `/cancel`, `/resume`, `/expire`, `/grant-lifetime`.

**Plans** (billing catalog) — `GET /plans`, `GET /plans/{id}`, `POST /plans`, `PUT /plans/{id}`, `PATCH /plans/{id}/active`, `PATCH /plans/{id}/visibility`.

**Revenue** — `GET /revenue/summary?from=&to=` (gross/fees/tax/net/refunds), `GET /revenue/by-plan`, `GET /revenue/by-country`.

**Promotions** — `GET /promotions/coupons`, `POST /promotions/coupons`, `GET /promotions/promo-codes`, `POST /promotions/promo-codes`, `PATCH …/{id}/disable` (reuses existing creation logic from `AdminBillingController`, adds list/disable).

**Feature flags** — `GET /flags`, `POST /flags`, `PUT /flags/{key}`, `POST /flags/{key}/overrides` (per-user or global), `DELETE /flags/{key}/overrides/{id}`.

**Webhooks** — `GET /webhooks?status=&provider=&type=`, `GET /webhooks/{id}` (payload + processing status), `POST /webhooks/{id}/retry`.

**System Jobs** — `GET /jobs/outbox?status=`, `GET /jobs/email-queue`, `GET /jobs/sync-queue`, `GET /jobs/dead-letters`, `POST /jobs/dead-letters/{id}/requeue`, `POST /jobs/{type}/{id}/retry` (reuses `SyncAdminService` + outbox repositories).

**Announcements** — `GET /announcements`, `POST /announcements`, `PUT /announcements/{id}`, `PATCH /announcements/{id}/active`, `DELETE /announcements/{id}`. (Plus the **public** `GET /api/announcements/active` the customer app reads — not under `/api/admin`.)

**Settings** — `GET /settings?category=`, `GET /settings/{key}`, `PUT /settings/{key}` (secret keys are read-only/masked).

**Audit** — `GET /audit?admin=&action=&targetType=&targetId=&from=&to=` (read-only, paginated; includes `reason`).

**Health** — `GET /health` (aggregated component statuses).

---

## 7. Admin React App Structure

New Vite app at `BunnyCal-web/apps/admin`, same stack (React 18 + TanStack Query + React Router 6 + Tailwind), consuming `@bunnycal/ui` + `@bunnycal/api-client` from the workspace.

```
BunnyCal-web/apps/admin/src
  ├─ main.tsx, App.tsx
  ├─ lib/            adminApiClient.ts (wraps @bunnycal/api-client, base = /api/admin)
  ├─ auth/           login redirect, role guard (RequireRole), session hook
  ├─ layout/         AdminShell (reuses ui AppShell/Sidebar/TopBar), AdminSidebar config
  ├─ command/        CommandPalette (⌘K / Ctrl-K) — global search + quick actions
  ├─ components/     DataTable, FilterBar, Pagination, SearchInput, MetricCard, StatusBadge,
  │                  ConfirmActionDialog, JsonViewer, BeforeAfterDiff, GlobalSearchBar  ← reusable primitives
  ├─ features/
  │    ├─ operations/  dashboard/    analytics/   search/
  │    ├─ users/       subscriptions/ plans/      revenue/
  │    ├─ promotions/  flags/         webhooks/   jobs/
  │    ├─ announcements/ settings/    audit/      health/
  └─ pages/          one route component per screen, composing features
```

### 7.1 Reusable building blocks (build once)

- **`DataTable`** — generic column defs, server-side pagination/sort, loading skeletons (reuse `ui/Skeleton`), empty state (reuse `ui/EmptyState`).
- **`FilterBar`** — declarative filter config (text/select/date-range) → query params.
- **`Pagination`**, **`SearchInput`** (debounced), **`MetricCard`**, **`StatusBadge`** (over `ui/Badge`).
- **`ConfirmActionDialog`** (over `ui/Dialog`) — every destructive/grant action (suspend, refund, cancel, grant-pro) routes through a confirm + **required reason field** that is sent to the request and stored in the audit log's `reason` column.
- **`JsonViewer`** / **`BeforeAfterDiff`** — for webhook payloads and audit before/after.
- **`GlobalSearchBar`** — top-bar input bound to `GET /api/admin/search`; results grouped by type (User / Subscription / Invoice / Booking / Webhook). The same query service backs the command palette.

### 7.2 Command Palette (⌘K / Ctrl-K)

A first-class navigation primitive (Linear/GitHub/VS Code style). Pressing ⌘K (or Ctrl-K) opens an overlay where the admin can:
- **Search** — type `john@gmail.com` → jump straight to that user/subscription/invoice (same `GET /search` service as the global bar).
- **Run quick actions** — type `grant pro`, `coupon`, `subscription`, `announcement` → jump to that screen/action.
- **Navigate** — fuzzy-match any of the 30–40 screens by name.

As the portal grows, this is dramatically faster than menu navigation and is built once, early (Phase 2 alongside Users). Implemented with a lightweight headless command-menu (e.g. `cmdk`) styled with `@bunnycal/ui` tokens.

### 7.3 Navigation, routing, layout

Single `AdminShell` with a left `Sidebar` whose items are role-filtered (a SUPPORT admin doesn't see Revenue). The top bar carries the **GlobalSearchBar** and a ⌘K hint. Routing via React Router v6 nested routes; a top-level `RequireRole` guard redirects unauthorized users to a "not authorized" page and lets API 403s clear the session.

```
Sidebar
  Operations          ← daily landing page (not Dashboard)
  Dashboard           (metrics)
  Analytics           (product analytics)
  Users
  Subscriptions
  Plans               (was "Billing Catalog")
  Revenue
  Promotions
  Feature Flags
  Webhooks
  System Jobs
  Audit Logs
  ── Content ──
  Announcements
  ── System ──
  Settings
  System Health
```

**Operations is the default route**, not Dashboard. It works like GitHub notifications — every morning the admin opens Operations and clears the action-needed list (failed webhooks, pending refunds, subs needing sync), then dives into a metric or user as needed.

### 7.4 User detail page (expanded)

Support requests frequently involve integrations, so the user-detail page has tabs:

```
Profile · Authentication · Subscription · Invoices · Bookings ·
Connected Calendars · Connected Zoom · Connected Teams · Feature Flags · Audit Timeline
```

- **Authentication** — linked OAuth identities/providers (from `AuthIdentity`), last login.
- **Connected Calendars / Zoom / Teams** — read from the calendar/conferencing modules; the most common support context.
- **Audit Timeline** — this user's slice of `admin_audit_logs` (every admin action taken on them, with reason).

Each tab reuses the same `DataTable`/`MetricCard` primitives. Other sidebar items follow the same list → detail pattern.

### 7.5 Plans page

The **Plans** screen lists plans (Free, Starter Monthly, Starter Annual, Enterprise, …). Opening one shows an edit form:

```
Name · Description · Price · Currency · Trial · Visibility · Dodo Product ID · Dodo Price ID · Status
```

This replaces the manual "edit IDs in the database after creating products in Dodo" workflow with a proper form, writing the `subscription_plans` columns from §3.3.

---

## 8. Implementation Phases

The biggest pain *today* is editing the database by hand to manage plans/IDs. So **Plans ships in Phase 1**, immediately after security — not buried later.

**Phase 0 — Security + scaffolding.** `admin_roles` table + `AdminRole`; JWT `roles` claim; filter authorities; `@EnableMethodSecurity` + `/api/admin/**` rule; admin CORS origin; OAuth redirect allowlist; bootstrap super-admin; `admin_audit_logs` table (with `reason`) + `AdminAuditService`. On the frontend, add workspace support **without moving the customer app** (§2.2): extract `packages/ui` + `packages/api-client` from the existing `src/`, repoint the customer app's imports, and scaffold `apps/admin` with shell, router, login, `RequireRole`. Retrofit existing `AdminBillingController`/`SyncAdminController` with `@PreAuthorize`. **Exit:** an admin can log in, reach a protected `/api/admin/ping`, the customer app still builds and behaves identically after the package extraction, and every write is auditable.

**Phase 1 — Plans + Users + Subscriptions.** Highest operational value; kills the manual-DB-edit pain immediately. Extend `subscription_plans` (`provider_product_id`, `visibility`) + Plans CRUD UI. `AdminUserController`/`Service` (incl. integrations tabs) and `AdminSubscriptionController`/`Service` (reusing `SubscriptionService`/provider). Build the reusable `DataTable`/`FilterBar`/`ConfirmActionDialog` and the expanded user-detail page. Suspend, grant/remove Pro, extend trial, sync/refresh/cancel/resume.

**Phase 2 — Dashboard + Command Palette + Global Search.** Dashboard metrics (totals, MRR, failed payments, refunds, conversion, growth charts) via aggregation read-models. Build `GET /search` + `GlobalSearchBar` + ⌘K command palette now that there are enough screens to navigate. Promotions list/disable over existing services.

**Phase 3 — Revenue.** Aggregation over invoices/refunds/transactions: gross/fees/tax/net/refunds, revenue by plan, revenue by country.

**Phase 4 — Operations.** The daily landing page: `GET /operations/summary` action-needed counts (failed webhooks/payments, pending refunds, subs needing sync, failed Dodo webhooks, recent support actions), each linking into its detail screen. Make Operations the default route.

**Phase 5 — Everything else.** Feature Flags (`feature_flags`/`overrides` tables + precedence chain + evaluation hook alongside `EntitlementService`); Product Analytics (signups, bookings, conversion, popular event, timezones, countries); Webhooks viewer + retry; System Jobs (outbox/email/sync/dead-letters over `SyncAdminService`); Announcements (table + public read endpoint + banner); dynamic Settings; System Health aggregation; Audit-log browser.

---

## 9. Migration Strategy

- Backend migrations are **purely additive** (`V106_0`+): new tables (`admin_roles`, `admin_audit_logs`, `feature_flags`, `feature_flag_overrides`, `announcements`, `app_settings`) and two new nullable/defaulted columns on `subscription_plans`. No data migration of existing subscriptions — they already reference `plan_id`.
- The JWT `roles` claim is **backward compatible**: absent/empty for existing tokens; existing customer behavior is unchanged because the principal type stays the userId UUID.
- **Frontend change is additive, not a relocation (recommended path, §2.2):** keep the customer app's `src/` at the repo root; only extract the shared `@bunnycal/ui` + `@bunnycal/api-client` packages and repoint the customer app's imports to them. The customer app's build/deploy is unchanged. The `src/` → `apps/customer/` move is an optional, off-critical-path cleanup *after* the portal is live (§2.2/§2.3). No second repository is created.
- Roll out auth changes (Phase 0) and verify customer flows are unaffected **before** building modules.
- Dynamic `app_settings` adopts **incrementally** — `SettingsService` falls back to existing `@Value`/`*Properties` defaults, so values move into the DB one at a time without breaking anything.
- Bootstrap the first `SUPER_ADMIN` via guarded migration/env so we're never locked out; thereafter role grants flow through the (audited) admin UI.
- The `@ConditionalOnProperty` flags on existing admin controllers act as a kill switch during cutover.

---

## 10. Risks & Recommendations

| Risk | Mitigation |
|---|---|
| **Open redirect** via OAuth `state`/redirect origin. | Strict allowlist of return origins; never reflect arbitrary values. |
| **Privilege escalation** if roles aren't in the token or the filter regresses to empty authorities. | Method security (`@PreAuthorize`) + URL matcher as redundant gates; integration tests asserting a customer token gets 403 on `/api/admin/**`. |
| **Admin token theft** (high blast radius). | Short admin access-token TTL; consider re-auth/step-up for the most destructive actions (lifetime grant, bulk refund); audit everything with IP/user-agent. |
| **Design-system drift** between two apps. | Both apps consume the shared `@bunnycal/ui` package in the workspace — single source of truth, no copying. |
| **Frontend change regresses the production customer app.** | **Don't move the customer app to enable the portal** (§2.2): keep `src/` at the root and only extract the shared packages, which the strict `ui/*` layering makes mechanical. The `src/`→`apps/customer/` relocation is deferred to an optional post-launch cleanup, off the critical path. |
| **Heavy dashboard/revenue queries** on the primary DB. | Use read-optimized aggregate queries, add covering indexes, cache metrics briefly; consider a materialized view later. Don't compute on every page load uncached. |
| **Over-engineering roles** before they're needed. | Ship Phase 0 enforcing only `ADMIN`/`SUPER_ADMIN`; reserve `SUPPORT/FINANCE/OPERATIONS` in the enum and tighten `@PreAuthorize` per endpoint as modules land. |
| **Feature flags conflicting with `PlanCatalog` entitlements.** | Define explicit precedence (override → global → default → entitlement) and document that flags *layer over* entitlements, not replace them. |
| **Auditing gaps.** | Centralize writes through audited service methods; code-review rule that every admin mutation calls `adminAuditService.record(...)`. |

### Recommendations
1. **Don't refactor what the feature doesn't require.** The admin portal needs shared packages + a new app, *not* a relocation of the production customer app. Keep `src/` at the root; extract `@bunnycal/ui` + `@bunnycal/api-client` beside it; defer the `apps/customer/` move to an optional post-launch cleanup (§2.1/§2.2). This keeps the riskiest step off the critical path.
2. **Do Phase 0 first and prove it in isolation** — the auth model + shared-package extraction are the linchpins; verify the customer app builds and behaves identically before scaffolding admin.
3. **Two repos, two apps** — `BunnyCal-api` + `BunnyCal-web` (customer at root + `apps/admin`). No third repository; a solo founder shouldn't carry the extra maintenance.
4. **Ship Plans in Phase 1** — it removes today's biggest pain (manual DB edits for product/price IDs) immediately.
5. **Extend, don't add, for plans** — `subscription_plans` is already the catalog.
6. **Reuse services aggressively**; admin services should be orchestrators (role + audit + aggregation), never reimplementations.
7. **Operations is the daily landing page**, not Dashboard — model it on GitHub notifications (action-needed list you clear each morning).
8. **Build the ⌘K command palette + global search early** (Phase 2) — it scales far better than menus across 30–40 screens.
9. **Treat the admin audit log as a product feature** — capture before/after + IP + **reason** from day one (`PaymentAuditLog` already proves the pattern works here).
10. **Keep secrets in the secret manager** — `app_settings` is for non-secret operational config only.
