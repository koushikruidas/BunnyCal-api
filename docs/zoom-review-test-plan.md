# BunnyCal — Zoom App Review Test Plan

> Copy this into a Google Doc (or export as PDF) and link it in the release notes of
> the next submission. Replace the credential placeholders before sharing.
> App: BunnyCal (User-managed OAuth app) — Client ID (Production): z74FJmlcSDG4AfuK2tzh2Q

## 1. Test credentials

| Item | Value |
|---|---|
| BunnyCal test account URL | https://bunnycal.io |
| Login method | Click **Sign in with Google** and use the Google credentials below |
| Google account email | `hotelregistar@gmail.com` |
| Google account password | `Being12@12` |
| Zoom account | Reviewer's own Zoom account (any Zoom user can authorize) |

If Google asks for a passcode or another verification step, choose **"Try another
way"** and sign in with the password.

Notes for reviewers: BunnyCal is a single-role product (one user = one host). There is
no second user role to test; invitees book via a public link without an account.
The production environment at bunnycal.io uses the **Production Client ID** for
authorization.

## 2. App authorization (scope: all)

1. Go to https://bunnycal.io and sign in with the test credentials.
2. Open **Dashboard → Integrations**.
3. In the Conferencing section, click **Connect** on the Zoom card.
4. You are redirected to zoom.us; the consent screen lists only:
   *View a user (user:read:user), Create/Update/Delete a meeting (meeting:write:meeting,
   meeting:update:meeting, meeting:delete:meeting)*.
5. Approve. You are redirected back to the BunnyCal Integrations page and the Zoom
   card shows **Connected**.

Scope exercised here: `user:read:user` — immediately after authorization BunnyCal
calls `GET /v2/users/me` once to store the Zoom user ID that maps the connection
(and lets us honor deauthorization events later). No other user data is read.

## 3. Create a booking → Zoom meeting created (scope: meeting:write:meeting)

1. In BunnyCal, open **Event Types** and create an event type (any name, 30 min);
   in the conferencing/location section choose **Zoom**; save/publish.
2. Open the event type's public booking link (copy-link button) in a private window.
3. Pick any available slot, enter an invitee name and an email you can check, book.
4. Expected: the booking confirmation shows a **Zoom join link**; the calendar
   invitation email contains the same link. In the Zoom web portal (Meetings),
   a meeting exists at the booked time (`POST /v2/users/me/meetings`).

## 4. Reschedule the booking (scope: meeting:update:meeting)

1. From the booking confirmation (or the manage-booking link in the email), choose
   **Reschedule** and pick a different slot.
2. Expected: the same Zoom meeting moves to the new time in the Zoom portal —
   the join link is unchanged (`PATCH /v2/meetings/{meetingId}`).

## 5. Cancel the booking (scope: meeting:delete:meeting)

1. From the manage-booking link, choose **Cancel**.
2. Expected: the Zoom meeting disappears from the Zoom portal
   (`DELETE /v2/meetings/{meetingId}`).

## 6. Repeat booking (token refresh / rotation)

1. Book the same event type again (new slot).
2. Expected: a new Zoom meeting is created successfully. This exercises the OAuth
   refresh flow including refresh-token rotation.

## 7. Disconnect from BunnyCal

1. **Dashboard → Integrations → Zoom → Disconnect**.
2. Expected: the card returns to **Not connected**. BunnyCal revokes the token with
   Zoom (`/oauth/revoke`) and deletes the stored token and Zoom user ID.
3. Booking the Zoom event type is no longer possible with Zoom conferencing until
   reconnected.

## 8. Deauthorization from Zoom (data deletion)

1. Reconnect Zoom (repeat §2).
2. In the Zoom web portal: **App Marketplace → Manage → Added Apps → BunnyCal →
   Remove**.
3. Expected: Zoom sends the `app_deauthorized` event to
   `https://api.bunnycal.io/integrations/conferencing/zoom/webhooks`; BunnyCal
   deletes all stored data for that Zoom user immediately (well within the 10-day
   requirement). The Integrations page shows Zoom as not connected.

## 9. End-user documentation

Public guide (add / use / remove, troubleshooting, support contact):
https://bunnycal.io/docs/zoom
