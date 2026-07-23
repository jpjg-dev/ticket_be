ALTER TABLE queue_feature_flags
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
