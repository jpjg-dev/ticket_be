ALTER TABLE payment_outbox_events
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_until TIMESTAMP WITH TIME ZONE;

ALTER TABLE payment_outbox_events
    ADD CONSTRAINT payment_outbox_events_claim_pair_check
        CHECK (
            (claim_token IS NULL AND claimed_until IS NULL)
            OR
            (claim_token IS NOT NULL AND claimed_until IS NOT NULL AND status = 'PENDING')
        );

CREATE INDEX idx_payment_outbox_unresolved_order
    ON payment_outbox_events (payment_id, id)
    WHERE status <> 'PUBLISHED';

CREATE INDEX idx_payment_outbox_claimable
    ON payment_outbox_events (status, next_retry_at, claimed_until, id);
