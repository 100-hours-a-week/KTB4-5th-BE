ALTER TABLE recommendation_outbox
    DROP CHECK ck_recommendation_outbox_status,
    ADD COLUMN topic VARCHAR(249) NULL,
    ADD COLUMN message_key VARCHAR(32) NULL,
    ADD COLUMN message_body JSON NULL COMMENT 'Kafka envelope 원문. HTTP 행은 NULL',
    ADD COLUMN next_attempt_at DATETIME(6) NULL,
    ADD COLUMN lease_token VARCHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD KEY ix_recommendation_outbox_pending (status, next_attempt_at),
    ADD KEY ix_recommendation_outbox_lease (status, lease_until),
    ADD CONSTRAINT ck_recommendation_outbox_status CHECK (status IN ('PENDING','PUBLISHING','SENT','FAILED'));
