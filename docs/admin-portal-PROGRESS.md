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
- **Phase 3 — Revenue (#5).** DONE. `io.bunnycal.admin.revenue` AdminRevenueController
  `GET /api/admin/revenue?from&to` (default 30d) + RevenueReportService + RevenueReportDto.
  MoR waterfall Gross→Fees→Refunds→Net + Payouts placeholder; by-plan (invoice→sub→plan join),
  over-time (native date_trunc daily). Aggregate queries added to Invoice repo (revenueTotals,
  revenueByPlan, revenueByDay, currenciesByVolume — projections) and Refund repo
  (sumSucceededMinorBetween). UI: features/revenue RevenuePage (waterfall + bar chart + by-plan
  table + range selector). HONEST GAPS: **fees are ESTIMATED** from
  `billing.fees.processor-percent-bps` (new config, default 0 = "not configured" → UI shows fees/net
  unavailable; per-txn fees not stored); **payouts** = placeholder (reconciliation not implemented);
  **by-country** = "not available yet" (no country stored on invoices). NOTE: BillingProperties got a
  new `Fees` record param — fixed the one test that constructed it directly. The native revenueByDay
  + JPQL plan-join compile but weren't run under auth (verify in manual wiring).
- **Promotions (#6).** DONE. Backend: new `io.bunnycal.admin.promotions` module with
  AdminPromotionController `/api/admin/promotions/coupons` and `/promo-codes` (paginated list over
  JPA Specifications, newest first) plus audited `PATCH .../{id}/disable` endpoints for coupons and
  promo codes. Reused the existing `AdminBillingController` create endpoints for coupon/promo-code
  creation and added the missing `AdminAuditService.record(...)` hooks to billing writes
  (coupon/promo creation, manual discount grant, refund issue). UI: features/promotions
  PromotionsPage with create cards + list/filter/disable tables, sidebar enabled, route wired.
  HONEST GAP: this module does **not** add a standalone manual-discount or refund browser yet;
  those actions remain surfaced from user/subscription flows and Revenue/Operations.
- **Phase 4 — Operations.** DONE. `io.bunnycal.admin.operations` AdminOperationsController
  `GET /api/admin/operations/summary` + OperationsService. Action-needed counts over existing
  systems: failed payments (last 30d), failed webhooks, pending refunds, past-due subscriptions,
  promo issues (active coupons/promo codes that are expired/exhausted/broken), and jobs needing
  attention (sync dead letters + outbox retrying/failed). UI: features/operations OperationsPage,
  sidebar enabled, and **Operations is now the default landing route** (`/` and fallthrough both
  redirect to `/operations`).
  HONEST GAPS: there is still **no dedicated failed-payments browser**, **no standalone
  subscriptions browser**; Operations links to the nearest existing surface where available and
  labels the missing drill-downs directly instead of faking actions. Pending refunds currently
  count `RefundStatus.PENDING` only; they are surfaced in Operations before a dedicated refund
  queue exists.
- **System Jobs.** DONE. `io.bunnycal.admin.jobs` AdminJobsController `/api/admin/jobs` +
  SystemJobsService. Backend: paginated outbox browser (`GET /outbox` with status / aggregate /
  event-type filters), sync dead letters (`GET /dead-letters` over existing SyncAdminService), and
  audited retry actions for outbox events (`POST /outbox/{id}/retry`) and sync dead letters
  (`POST /dead-letters/{id}/requeue`). UI: features/jobs SystemJobsPage, sidebar enabled, and
  Operations now links into a real Jobs screen. HONEST GAP: there is **no separate persisted
  email queue** in the current system; email delivery runs through `outbox_events`, so the Jobs UI
  calls that out explicitly instead of inventing a fake queue.
- **Feature Flags (#7).** DONE. `V109_0__feature_flags.sql` adds `feature_flags` and
  `feature_flag_overrides` (seeded from the existing `Feature` enum). Backend:
  `io.bunnycal.admin.flags` FeatureFlagService + AdminFeatureFlagController `/api/admin/flags`,
  plus the entitlement evaluation hook now runs through FeatureFlagService inside
  `EntitlementServiceImpl`. Precedence implemented as:
  **kill switch (`enabled=false`) → per-user override → global override → `default_value=true`
  → `PlanCatalog` fallback**. UI: features/flags FeatureFlagsPage with definition editing,
  global/user override upsert, inspected-user effective-value preview by UUID, and audited
  override deletion.
  HONEST GAPS: this first pass covers the existing boolean `Feature` enum only — no numeric-limit
  overrides, no user search/picker in the Flags UI (UUID entry only), and no inline flags tab on
  the user detail page yet.
- **Phase 5 — everything else:**
  - **Webhooks (#8)** — DONE (viewer only). `io.bunnycal.admin.webhooks` AdminWebhookController
    `GET /api/admin/webhooks` (paginated, filter status/provider/type via Specification) + `GET /{id}`
    (with raw payload). WebhookEventRepository now extends JpaSpecificationExecutor. UI:
    features/webhooks WebhooksPage (filter bar + table + expandable payload, lazy-loads detail).
    **RETRY DEFERRED**: the domain handler routes on ProviderWebhookEvent.data() (pre-extracted
    fields) which is NOT persisted — only rawPayload is — and the parser is coupled to signature
    verification inside DodoProvider.verifyWebhook. A faithful reprocess needs a parse/verify split
    in the payments core (don't ride it along with a read-only module). Provider redelivery remains
    the recovery path.
  - **Audit Logs (#9)** — DONE. `io.bunnycal.admin.audit` AdminAuditController
    `GET /api/admin/audit` (paginated, filters via JPA Specification) + AdminAuditQueryService +
    AdminAuditLogDto; repo extends JpaSpecificationExecutor. UI: features/audit AuditLogPage
    (filter bar + table + expandable before/after JSON). Added reusable backend
    `io.bunnycal.admin.common.PageResponse<T>` and frontend `components/Pagination` +
    `PageResponse<T>` type (in features/audit/types.ts — promote to shared lib on 2nd consumer).
  - **System Health (#10)** — aggregate Actuator + provider/integration pings.
  - **Analytics** (product, not revenue) — signups, bookings created/cancelled, conversion,
    popular event, timezones, countries.
  - **Announcements** — NEW `announcements` table + public read endpoint + banner.
  - **Settings** — NEW `app_settings` table (non-secret dynamic config), SettingsService with
    `@Value` fallback.

## Rough remaining size
~11 of ~16 modules done (Plans, Users, Subscriptions, Dashboard, Audit viewer, Webhooks viewer,
Revenue, Promotions, Operations, System Jobs, Feature Flags) + both foundations. Per agreed
order, next: **Analytics → Announcements → Settings → Global Search/⌘K → Dashboard growth
charts**. Plus Webhooks retry (needs payments-core parse/verify split).

Frontend shared pieces promoted: `lib/pagination.ts` now holds the generic `PageResponse<T>`
(features/audit/types.ts re-exports it). Reuse it + `components/Pagination` + `components/MetricCard`
in new modules.

## Agreed build order from here (user, 2026-06-30)
1. **Revenue** — MoR waterfall: Gross → Dodo Fees → Refunds → Net → **Payouts (placeholder
   section even if not implemented)**. Plus revenue by plan / by country / over time. DONE.
2. **Promotions** — UI over existing PromotionService/RefundService + list/disable. DONE.
3. **Operations** — make it the DEFAULT landing page (failed payments/webhooks, pending refunds,
   past-due subs, promo issues, jobs needing attention). DONE.
4. **System Jobs** — outbox/email/sync/dead-letters. DONE.
5. **Feature Flags** — data-backed overrides layered over plan entitlements. DONE.
6. Analytics · 7. Announcements · 8. Settings · 9. Global Search + ⌘K · 10. Dashboard growth
charts.

## Open decisions for the user
1. reset-onboarding semantics (or drop it).
2. When to push branches / open PRs (nothing pushed yet).
3. Whether to wire a real Dodo provider-fetch for subscription sync.
