ALTER TABLE recommendation_outbox
    ADD COLUMN db_job BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN job_snapshot JSON NULL,
    ADD COLUMN job_expires_at DATETIME(6) NULL;
