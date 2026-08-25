CREATE INDEX idx_payment_outbox_published_cleanup
    ON payment_outbox_events (published_at, id)
    WHERE status = 'PUBLISHED';
