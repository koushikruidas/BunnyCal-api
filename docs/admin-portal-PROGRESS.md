# Admin Portal — Build Progress & Handoff

> **Purpose:** Single source of truth for what's done, what's pending, and how to resume.
> Read this first, then `docs/admin-portal-plan.md` (the full architecture spec).
> **Last updated:** 2026-06-30

---

## Where to look (documents & key files)

| What | Where |
|---|---|
| **Full architecture spec** (the plan) | `BunnyCal-api/docs/admin-portal-plan.md` |
| **This progress/handoff doc** | `BunnyCal-api/docs/admin-portal-PROGRESS.md` |
| Backend admin code | `BunnyCal-api/src/main/java/io/bunnycal/admin/**` |
| Backend security (Phase 0) | `auth/security/**`, `auth/security/jwt/JwtTokenProvider.java`, `auth/security/filter/JwtAuthenticationFilter.java`, `auth/security/config/SecurityConfig.java` |
| Migrations | `BunnyCal-api/src/main/resources/db/migration/V106..V108` |
| Admin frontend | `BunnyCal-web/apps/admin/**` |
| Shared design system | `BunnyCal-web/packages/ui` (`@bunnycal/ui`), `BunnyCal-web/packages/tailwind-preset` |

## Repos & branches (all LOCAL — not pushed, no PRs)

- **`BunnyCal-api`** → branch `feature/admin-portal-phase0`
  - `ea73cc5` Phase 0 security · `7e5a360` Plans · `5f1fcde` Users + Subscriptions
- **`BunnyCal-web`** → branch `feature/admin-portal-phase1-scaffold`
  - `bc6597b` scaffold · `30295bb` Plans UI · `e348f94` Users + Subscriptions UI

## Environment facts (verified this session)

- Backend runs from IntelliJ on `localhost:8080`; **hot-reloads** new controllers/migrations.
- Postgres is dockerized: container `bunnycal-api-postgres-1`, db `bunnycal`, user `bunnycal`,
  password in the container env (`docker exec ... env | grep POSTGRES`).
- Migrations V106/V107/V108 are **applied**. `koushikruidas@gmail.com`
  (id `ffb3a1e6-cf33-4d92-b05d-ce5e1596f290`) was granted **SUPER_ADMIN** directly in
  `admin_roles` for manual wiring.
- Customer app: `npm run dev` (port 5173). Admin app: `npm run dev:admin` (port 5174).
- Verify gates with: `npm run lint` / `npm run build` (customer), `npm run lint:admin` /
  `npm run build:admin` (admin), and `./gradlew compileJava compileTestJava` (backend).

## Architecture invariants (do not break)

- All admin endpoints live under `/api/admin/**`, gated by the URL rule in `SecurityConfig`
  **plus** a class-level `@PreAuthorize`. Roles: ADMIN, SUPER_ADMIN, SUPPORT, FINANCE, OPERATIONS.
- Phase 0 enforces ADMIN/SUPER_ADMIN broadly; tighten per-endpoint as modules land.
- Every state-changing admin action MUST call `AdminAuditService.record(...)` with
  before/after + reason (writes `admin_audit_logs`).
- Admin services are **orchestrators** over existing billing/auth services — never reimplement
  billing logic. Keep customer-facing services (PlanService, SubscriptionService) untouched.
- Frontend: customer app **stays at repo root**; do NOT move it to `apps/customer` to ship
  features (that's an optional post-launch cleanup). Shared code goes through `@bunnycal/ui` /
  `@bunnycal/tailwind-preset`. Admin app has its own small `adminApiClient` (not the customer one).
- New migrations: next is **V109** (sequential `V<n>_0__name.sql`).

---

## DONE

### Foundations
- **Phase 0 security**: `AdminRole` + `admin_roles` (V106), JWT `roles` claim, filter maps to
  `ROLE_*` authorities, `@EnableMethodSecurity`, `/api/admin/**` rule, admin CORS origin,
  `AdminBootstrapRunner` (`ADMIN_BOOTSTRAP_EMAIL`), `admin_audit_logs` (V107) + `AdminAuditService`,
  `AdminPingController` (`/api/admin/ping`). Retrofitted AdminBilling/SyncAdmin controllers with
  `@PreAuthorize`.
- **Frontend foundation**: npm workspace; `@bunnycal/ui` (git-mv'd from `src/ui`) +
  `@bunnycal/tailwind-preset`; `apps/admin` (port 5174) with shell, router, Google login,
  `useAdminSession` (probes ping), `RequireRole`, AdminShell/Sidebar, `adminApiClient`,
  `ConfirmActionDialog`.

### Modules
- **Plans** (catalog #4): V108 adds `provider_product_id` + `visibility` to `subscription_plans`;
  `PlanVisibility` enum; `io.bunnycal.admin.plans` (AdminPlanController `/api/admin/plans`,
  PlanCatalogService, PlanDto/PlanRequests). UI: PlansListPage + PlanFormPage. Replaces manual
  DB editing of Dodo product/price ids.
- **User Management** (#2): `io.bunnycal.admin.users` — search (email/userId/subId/dodoCustomerId),
  detail (profile+subscriptions+invoices+entitlements), suspend/unsuspend/grant-pro/remove-pro/
  grant-lifetime. UI: UsersSearchPage, UserDetailPage (tabs).
- **Subscription Management** (#3): `io.bunnycal.admin.subscriptions` — get/refresh/cancel/resume/
  expire/extend-trial/grant-lifetime. UI: SubscriptionPanel inside user detail.
- **Dashboard** (#1, metrics only): `io.bunnycal.admin.dashboard` — AdminDashboardController
  `/api/admin/dashboard/metrics`, DashboardMetricsService. Metrics: total/active/new users,
  paid/trial/free/past-due, MRR, revenue/refunds/failed-payments (30d), conversion. Aggregate
  repo queries added to User/Subscription/Invoice/Refund/PaymentTransaction repos. UI: MetricCard +
  DashboardPage (replaced the ping page). NOTE: growth time-series + ⌘K palette + global search
  still pending for Phase 2. MRR uses a JPQL cross-join (Subscription↔SubscriptionPlan) — verify it
  executes during manual wiring (compiles; not yet run with auth).

### Known gaps in DONE items (intentional)
- **reset-onboarding**: NOT built — no onboarding field on `User`. Define the reset semantics first.
- **Subscription refresh/sync**: currently a local re-read. `PaymentProvider` has no
  get-subscription method; wire a real Dodo fetch later (one-method change).
- **Standalone Subscriptions browser**: none; subscriptions are managed from user detail.
  Sidebar "Subscriptions" item is disabled.

---

## PENDING (by plan phase)

> Sidebar items already exist as disabled placeholders in
> `apps/admin/src/layout/navConfig.ts` — enable as each ships.

- **Phase 2 — Dashboard DONE (metrics).** Still pending: **growth time-series** chart, and the
  **⌘K command palette + global search** (`GET /api/admin/search` fan-out over
  users/subs/invoices/bookings/webhooks).
- **Phase 3 — Revenue (#5).** gross/fees/taxes/net/refunds, by-plan, by-country. Aggregation
  over invoices/refunds/payment transactions.
- **Phase 4 — Operations** (daily landing page). `GET /api/admin/operations/summary`
  action-needed counts (failed webhooks/payments, pending refunds, subs needing sync). Make it
  the default route.
- **Phase 5 — everything else:**
  - **Feature Flags (#7)** — NEW tables `feature_flags` + `feature_flag_overrides` (V109/V110),
    FeatureFlagService with precedence (per-user override → global → default → PlanCatalog),
    evaluation hook alongside EntitlementService.
  - **Webhooks (#8)** — read-only viewer + retry over existing `webhook_events` table +
    WebhookIngestionService. Light.
  - **Audit Logs (#9)** — browser over `admin_audit_logs` (already being written). Light;
    good quick win.
  - **System Health (#10)** — aggregate Actuator + provider/integration pings.
  - **Analytics** (product, not revenue) — signups, bookings created/cancelled, conversion,
    popular event, timezones, countries.
  - **System Jobs** — outbox/email/sync/dead-letters over SyncAdminService + outbox repos.
  - **Announcements** — NEW `announcements` table + public read endpoint + banner.
  - **Settings** — NEW `app_settings` table (non-secret dynamic config), SettingsService with
    `@Value` fallback.
- **Promotions (#6)** — list/disable UI over existing PromotionService/RefundService +
  AdminBillingController (creation already exists). Mostly UI.

## Rough remaining size
~4 of ~16 modules done (Plans, Users, Subscriptions, Dashboard) + both foundations. Light
remaining: Webhooks, Audit viewer, Promotions, System Jobs. Medium: Revenue, Analytics (new
queries), Feature Flags, Announcements, Settings (new tables). One focused build: ⌘K palette +
global search. Plus Dashboard growth time-series.

## Open decisions for the user
1. reset-onboarding semantics (or drop it).
2. When to push branches / open PRs (nothing pushed yet).
3. Whether to wire a real Dodo provider-fetch for subscription sync.
