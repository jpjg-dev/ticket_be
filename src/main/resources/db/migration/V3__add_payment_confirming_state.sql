ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS confirming_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS payments_status_check;

ALTER TABLE payments
    ADD CONSTRAINT payments_status_check
        CHECK (status IN ('READY', 'CONFIRMING', 'APPROVED', 'FAILED', 'CANCELED'));
