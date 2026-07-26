-- Dodo payment.succeeded payloads do not carry previous_billing_date or
-- next_billing_date. Subscription webhooks do, and the subscription state is updated
-- immediately before/around the corresponding payment webhook. Backfill only receipts
-- issued at the start of the currently stored period so older historical receipts can
-- never be assigned a newer period accidentally.
UPDATE subscription_invoices AS receipt
SET period_start = COALESCE(receipt.period_start, subscription.current_period_start),
    period_end = COALESCE(receipt.period_end, subscription.current_period_end)
FROM subscriptions AS subscription
WHERE receipt.subscription_id = subscription.id
  AND (receipt.period_start IS NULL OR receipt.period_end IS NULL)
  AND subscription.current_period_start IS NOT NULL
  AND subscription.current_period_end IS NOT NULL
  AND receipt.issued_at BETWEEN subscription.current_period_start - INTERVAL '5 minutes'
                            AND subscription.current_period_start + INTERVAL '5 minutes';
