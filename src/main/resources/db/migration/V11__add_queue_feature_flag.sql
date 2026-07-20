CREATE TABLE queue_feature_flags
(
    id         BIGINT      NOT NULL,
    queue_mode VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT queue_feature_flags_pkey PRIMARY KEY (id),
    CONSTRAINT queue_feature_flags_singleton_check CHECK (id = 1),
    CONSTRAINT queue_feature_flags_mode_check CHECK (queue_mode IN ('OFF', 'SHADOW', 'ENFORCED'))
);

INSERT INTO queue_feature_flags (id, queue_mode, updated_at)
VALUES (1, 'OFF', CURRENT_TIMESTAMP);
