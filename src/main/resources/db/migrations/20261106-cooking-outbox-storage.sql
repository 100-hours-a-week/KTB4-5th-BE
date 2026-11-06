-- 기존 DB에는 이 버전의 애플리케이션을 배포하기 전에 수동 적용한다.
CREATE TABLE cooking_outbox (
    cooking_record_id BIGINT NOT NULL,
    message_key VARCHAR(20) NOT NULL COMMENT 'Kafka key로 쓰는 userId 문자열',
    message_body JSON NOT NULL COMMENT '메시지 ID와 차감 내역이 포함된 Kafka 본문',
    publish_after DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    PRIMARY KEY (cooking_record_id),
    KEY ix_cooking_outbox_pending (published_at, publish_after, cooking_record_id),
    CONSTRAINT fk_cooking_outbox_record FOREIGN KEY (cooking_record_id) REFERENCES cooking_records (cooking_record_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
