-- 기존 DB에 애플리케이션 배포 전에 수동 적용한다. 신규 DB는 schema.sql을 사용한다.
CREATE TABLE recommendation_daily_quotas (
    user_id BIGINT NOT NULL,
    quota_date DATE NOT NULL,
    request_ids JSON NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_recommendation_quota_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
