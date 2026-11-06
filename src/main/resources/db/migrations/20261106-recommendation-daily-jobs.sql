-- 일일 갱신 작업 저장·선점용 테이블이다. 기존 DB에 수동 적용한다.
CREATE TABLE recommendation_daily_jobs (
    recommendation_daily_job_id BIGINT NOT NULL AUTO_INCREMENT,
    business_date DATE NOT NULL,
    user_id BIGINT NOT NULL,
    refrigerator_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    recommendation_list_id BIGINT NULL,
    reason VARCHAR(64) NULL,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (recommendation_daily_job_id),
    UNIQUE KEY uk_recommendation_daily_participant (business_date, user_id, refrigerator_id),
    UNIQUE KEY uk_recommendation_daily_request (request_id),
    KEY ix_recommendation_daily_ready (business_date, status, lease_until, recommendation_daily_job_id),
    CONSTRAINT fk_recommendation_daily_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_recommendation_daily_refrigerator FOREIGN KEY (refrigerator_id) REFERENCES refrigerators (refrigerator_id),
    CONSTRAINT fk_recommendation_daily_list FOREIGN KEY (recommendation_list_id) REFERENCES recommendation_lists (recommendation_list_id),
    CONSTRAINT ck_recommendation_daily_status CHECK (status IN ('PENDING','CLAIMED','ACCEPTED','REUSED','SKIPPED','COMPLETED','FAILED')),
    CONSTRAINT ck_recommendation_daily_lease CHECK (
        (status = 'CLAIMED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'CLAIMED' AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
