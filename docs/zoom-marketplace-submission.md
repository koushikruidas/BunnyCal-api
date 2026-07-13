# Zoom Marketplace: Dev Setup & Production Submission Guide

App: `R5uayMuBRQmj631KDmiahw` (BunnyCal — user-managed OAuth app)
Callback: `/integrations/conferencing/zoom/callback` · Webhook: `/integrations/conferencing/zoom/webhooks`

---

## 1. Local development setup (the localhost problem)

**Empirical finding (2026-07-02):** Zoom's OAuth authorize endpoint rejects ALL
plain-http redirect URIs — `localhost` *and* `http://127.0.0.1` — with error
4,700, even when the loopback URL is the registered Development redirect URL.
The portal form accepts loopback registration and its inline error text claims
`http://127.0.0.1` works "for local testing"; that guidance is stale — tested
for 25+ minutes on both `zoom.us/oauth/authorize` and
`marketplace.zoom.us/v2/authorize`. Other findings: Zoom normalizes double
slashes in redirect URIs, matches at the domain level when Strict Mode is off,
and the OAuth Allow List does **not** gate authorize-time validation — only the
registered redirect URL field does.

So local dev needs an **HTTPS tunnel with a static domain**:

1. One-time: sign up at https://dashboard.ngrok.com (free), then
   `ngrok config add-authtoken <token>`, and claim your free static domain
   (Dashboard → Domains), e.g. `koushik-bunnycal.ngrok-free.app`.
2. In the Zoom app's Development tab, set the **OAuth Redirect URL** to
   `https://<static-domain>/integrations/conferencing/zoom/callback`.
3. In `BunnyCal-api/.env`:
   `ZOOM_REDIRECT_URI=https://<static-domain>/integrations/conferencing/zoom/callback`
   (keep Development-tab client ID/secret; frontend redirects stay on
   `http://localhost:5173/...` — that hop is outside Zoom's rules).
4. While testing Zoom OAuth locally, run:
   `ngrok http --domain=<static-domain> 8080`
5. Bonus: the same tunnel lets Zoom reach your local deauthorization webhook —
   set the Development event notification URL to
   `https://<static-domain>/integrations/conferencing/zoom/webhooks`.
   Zoom validates it with an `endpoint.url_validation` challenge, which the
   backend answers automatically once `ZOOM_WEBHOOK_SECRET_TOKEN` is set.

⚠️ The live client secret currently sits in `BunnyCal-api/.env` (already marked
`ROTATE`). Rotate it in the Zoom portal before submission — reviewers can ask
about secret handling.

---

## 2. Production app configuration checklist

- [ ] OAuth Redirect URL: `https://api.bunnycal.io/integrations/conferencing/zoom/callback` (exact match with `ZOOM_REDIRECT_URI` in prod `.env`).
- [ ] Event notification endpoint URL: `https://api.bunnycal.io/integrations/conferencing/zoom/webhooks` — subscribe to the **App Deauthorized** event.
- [ ] Copy the app's **Secret Token** into prod env as `ZOOM_WEBHOOK_SECRET_TOKEN` and redeploy **before** saving the URL (Zoom fires the URL-validation challenge immediately).
- [ ] Scopes: only the four granular scopes in §3 — remove anything else; every extra scope triggers a clarification round.
- [ ] Listing URLs: Documentation `https://bunnycal.io/docs/zoom`, Privacy Policy, Terms of Use, Support contact (`support@bunnycal.io`). All must be live, public, and mention Zoom by name where relevant.
- [ ] Direct landing URL ("Add" flow): a page where a reviewer can sign up and click **Connect Zoom** — `https://bunnycal.io/docs/zoom` documents this; the actual connect lives in Dashboard → Integrations.
- [ ] Test account for reviewers: create a BunnyCal account (e.g. `zoom-review@bunnycal.io`) with a seeded event type, and include credentials in the submission's test instructions.
- [ ] Demo video (reviewers usually require one): show sign-in → connect Zoom (OAuth consent) → create booking → Zoom meeting appears with join URL → reschedule → cancel → disconnect.

---

## 3. Scope justification (copy-paste for the questionnaire)

BunnyCal is a scheduling tool; the Zoom integration exists solely to attach a
Zoom meeting to bookings made through a user's scheduling page.

| Scope (granular / classic) | Zoom API call | Why |
|---|---|---|
| `user:read:user` / `user:read` | `GET /v2/users/me` (once, at connect) | Store the Zoom user ID so we can map the connection and honor deauthorization events. |
| `meeting:write:meeting` / `meeting:write` | `POST /v2/users/me/meetings` | Create the meeting when a booking is confirmed. |
| `meeting:update:meeting` | `PATCH /v2/meetings/{id}` | Update topic/time when a booking is rescheduled. |
| `meeting:delete:meeting` | `DELETE /v2/meetings/{id}` | Remove the meeting when a booking is cancelled. |

We request **no** other scopes: no recordings, no participants, no chat, no
account-level data. All calls are on behalf of the authenticated user (`me`).

---

## 4. Data handling answers (grounded in the code)

**What Zoom data do you store?**
Per connected user: the Zoom user ID, an encrypted OAuth refresh token, the
token expiry timestamp, and a connection status
(`zoom_conferencing_connections` table). Per booking: the Zoom meeting ID and
join URL so the invitation email/calendar event can include it. We never store
meeting content, recordings, transcripts, chat, or participant data.

**How are tokens protected?**
- At rest: refresh tokens are encrypted with AES-256-GCM (`AesGcmTokenCipher`)
  before persistence; the key is provided via environment variable, not in code.
- Access tokens are never persisted — they are obtained on demand via refresh
  and held only in memory for the duration of the API call.
- Refresh-token rotation is honored: each refresh response's new refresh token
  replaces the stored ciphertext immediately.
- In transit: all traffic is TLS 1.2+ (HTTPS termination at the reverse proxy);
  all Zoom API calls go to `https://api.zoom.us` / `https://zoom.us`.
- OAuth `state` is signed and validated server-side to prevent CSRF on the
  callback; the callback exchanges the code server-to-server (client secret
  never reaches the browser).

**How is data deleted?**
1. **User disconnects Zoom in BunnyCal** → we call Zoom's `/oauth/revoke` and
   delete the connection row (token material and Zoom user ID are erased
   immediately).
2. **User deletes their BunnyCal account** → the account-deletion worker runs
   the Zoom cleanup strategy, which performs the same revoke + delete.
3. **User removes BunnyCal from the Zoom App Marketplace** → Zoom sends
   `app_deauthorized` to our webhook endpoint; we verify the `x-zm-signature`
   HMAC (with replay protection) and delete all stored data for that Zoom user
   immediately — well within Zoom's 10-day requirement.

**Retention:** Zoom data is retained only while a connection is active. There
is no backup-based retention of revoked tokens; deletion is a hard delete.

**Who can access the data?** No human access in the normal course of
operation; tokens are ciphertext in the database. Access to production
infrastructure is limited to the operator (founder) with key-based auth.

---

## 5. Actual reviewer feedback (Jun 02, 2026) and status

Retrieved from the Production tab review thread on 2026-07-02.

| # | Reviewer item | Status |
|---|---|---|
| 1 | Long Description: remove security/privacy/compliance representations | ✅ Fixed — long_description rewritten via manifest (OAuth 2.0 mention removed) |
| 2 | OAuth Allowlist: use FQDNs, delete localhost URLs | ✅ Dev redirect URL is now the FQDN `https://api.bunnycal.io/...`; decision pending on the `http://127.0.0.1:8080` allow-list entry (portal-sanctioned; see note below) |
| 3 | Long Description: describe your company | ✅ Fixed — "About BunnyCal" paragraph added via manifest |
| 4 | Redirect Localhost (same as #2) | ✅ Same fix |
| 5 | Account Credentials: provide test account; MUST authorize with Production Client ID | ✅ Done 2026-07-02 — reviewer Google account `hotelregistar@gmail.com` in test plan (login verified end-to-end); VM `.env` switched to Production credentials and verified live (connect flow carries `client_id=z74FJmlcSDG4AfuK2tzh2Q`) |
| 6 | OAuth Scopes: remove unused scopes | ✅ Fixed — trimmed to the 4 used scopes; `meeting:read:meeting`, `user:read:email`, `zoomapp:inmeeting` removed |
| 7 | Test Plan: step-by-step guide covering every scope — "Blocker to test" | ✅ Drafted in `docs/zoom-review-test-plan.md` — copy to Google Doc, link in release notes |
| 8 | Free Account: add credit card OR complete compliance Google form | ✅ Verified 2026-07-02 — credit card saved as Primary Payment Method in Zoom Billing Management |
| 9 | Documentation URL must cover Adding / Usage / Removing (incl. deauthorization data handling) | ✅ `doc_url` now points to `https://bunnycal.io/docs/zoom`; page's Data Handling section expanded — deploy BunnyCal-web |

**127.0.0.1 note:** the portal's own validation message explicitly permits
`http://127.0.0.1` for local testing, but reviewer item #2 asks for FQDNs only. If
they push back again, the fallback is an HTTPS tunnel with a static domain (e.g.
ngrok) as the dev redirect, and drop the loopback entry.

## 6. Typical reviewer clarifications & suggested responses

- **"We could not complete a second booking / integration stopped working"** —
  was caused by refresh-token rotation not being persisted; fixed in
  `ZoomConferencingProvider.accessToken()` (rotated token is now re-encrypted
  and saved on every refresh). Re-test create → wait → create again.
- **"Describe your deauthorization handling"** — see §4.3; endpoint is
  `POST /integrations/conferencing/zoom/webhooks`, signature-verified,
  deletes data immediately.
- **"Why does your app need X scope"** — answer only from the table in §3;
  if they name a scope not in the table, remove it from the app instead of
  justifying it.
- **"Provide a TDD / security overview"** — single-tenant Spring Boot API +
  React SPA; PostgreSQL; hosting provider + region (fill in); TLS everywhere;
  AES-256-GCM token encryption; JWT-based user auth; webhooks verified by
  HMAC-SHA256; no third-party subprocessors receive Zoom data.
- **"App name / marketing"** — the listing must not lead with "Zoom"
  (e.g. "BunnyCal for Zoom" is fine, "Zoom Scheduler" is not), and screenshots
  must match the current UI.

---

## 7. What changed in the codebase (2026-07-02)

- `HttpZoomApiClient` / `ZoomApiClient.TokenRefreshResult`: refresh responses now
  carry the rotated refresh token.
- `ZoomConferencingProvider`: persists the rotated refresh token (fixes
  integration breaking after first refresh).
- `ZoomConferencingOAuthService`: `disconnect` now hard-deletes the connection
  after revoking; new `handleDeauthorized(providerUserId)`.
- New `ZoomWebhookController`: `endpoint.url_validation` challenge +
  `app_deauthorized` handling, HMAC signature verification, replay protection.
- `SecurityConfig`: webhook path permitted (signature-authenticated, like
  billing webhooks).
- Config: `zoom.oauth.webhook-secret-token` (`ZOOM_WEBHOOK_SECRET_TOKEN`) in
  `application.yaml`, `application-prod.yaml`, `.env.example`.
