ALTER TABLE calendar_connections
    ADD COLUMN IF NOT EXISTS account_classification VARCHAR(32),
    ADD COLUMN IF NOT EXISTS organizer_invite_delivery VARCHAR(32);

COMMENT ON COLUMN calendar_connections.account_classification IS
    'Provider-side account tier observed at runtime. '
    'Values: PERSONAL_MSA (consumer outlook.com / hotmail / live), '
    'AAD_WORK_SCHOOL (Microsoft 365 / Exchange Online tenant), '
    'GOOGLE (Google Workspace or @gmail.com), '
    'UNKNOWN. Backfilled lazily by the provider client on first successful CREATE.';

COMMENT ON COLUMN calendar_connections.organizer_invite_delivery IS
    'How the organizer-side meeting invitation reaches attendees. '
    'PROVIDER_NATIVE: the calendar provider dispatches the invite server-side (Google with sendUpdates=all; AAD/Exchange-Online via Graph). '
    'BACKEND_ICS_FALLBACK: provider will not dispatch (consumer MSA mailboxes); '
    'backend emits an ICS METHOD:REQUEST attachment via the notification path. '
    'UNKNOWN: pre-stamping or unrecognised mailbox; treat as PROVIDER_NATIVE for safety.';
