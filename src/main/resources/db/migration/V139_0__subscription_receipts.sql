-- BunnyCal is not the legal seller when subscription billing uses Dodo Payments as
-- Merchant of Record. Its generated document is therefore a payment receipt, while
-- Dodo's document remains the official tax invoice.

-- Preserve the existing sequence values while making all BunnyCal document references
-- unambiguously receipt numbers.
UPDATE subscription_invoices
SET invoice_number = 'BCR-' || substring(invoice_number FROM 4)
WHERE invoice_number LIKE 'BC-%';

ALTER TABLE subscription_invoices
    ADD COLUMN official_invoice_number VARCHAR(255),
    ADD COLUMN official_invoice_url VARCHAR(2048);
