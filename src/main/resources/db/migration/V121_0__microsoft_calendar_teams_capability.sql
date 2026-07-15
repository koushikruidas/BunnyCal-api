-- Microsoft Teams provisioning is a capability of the target calendar, not merely of the account
-- type. Graph exposes it through calendar.allowedOnlineMeetingProviders.
ALTER TABLE calendar_connection_calendars
    ADD COLUMN IF NOT EXISTS supports_native_teams BOOLEAN;
