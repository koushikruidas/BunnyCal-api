-- Trial duration is catalog data, not a dynamic application setting.
-- subscription_plans.trial_days is authoritative for new trials, while each started
-- trial retains its own deadline in subscriptions.trial_end.
DELETE FROM app_settings
WHERE key = 'billing.trial_days';
