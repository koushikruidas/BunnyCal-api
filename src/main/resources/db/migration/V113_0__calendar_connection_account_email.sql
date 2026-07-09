-- Google connections resolve their display email via the app's own User table
-- (the connected Google account is always the login identity in this app).
-- Microsoft connections are just linked calendars and can belong to a
-- different account than the login identity, so they need their own stored
-- email captured from Graph at connect-time. provider_user_id stays untouched
-- (MicrosoftAccountClassifier depends on its exact puid/oid shape).
ALTER TABLE calendar_connections
    ADD COLUMN account_email VARCHAR(255);
