-- 기존 DB에는 Kafka 경로를 켜기 전에 수동 적용한다.
CREATE TABLE expiration_checks (
    expiration_check_id BIGINT NOT NULL AUTO_INCREMENT,
    business_date DATE NOT NULL,
    refrigerator_id BIGINT NOT NULL COMMENT '재전달 중복 판정을 위해 냉장고 삭제 후에도 유지하는 식별자',
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publish_after DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (expiration_check_id),
    UNIQUE KEY uk_expiration_checks_date_refrigerator (business_date, refrigerator_id),
    KEY ix_expiration_checks_pending (published_at, publish_after, expiration_check_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
