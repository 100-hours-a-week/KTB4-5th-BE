-- 운영 자동 실행 파일이 아니다. 구버전 인스턴스를 내린 뒤 검증된 백업과 함께 적용한다.
-- 기존 PK/FK와 데이터는 보존한다. 캡처를 켜기 전까지 사건 순번은 0이다.
ALTER TABLE refrigerators ADD COLUMN membership_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE refrigerator_members
    ADD COLUMN joined_sequence BIGINT NULL,
    ADD COLUMN joined_type VARCHAR(20) NULL,
    ADD COLUMN joined_occurred_at DATETIME(6) NULL,
    ADD COLUMN joined_title VARCHAR(150) NULL,
    ADD COLUMN joined_body VARCHAR(500) NULL,
    ADD COLUMN joined_recipient_user_ids JSON NULL,
    ADD COLUMN ended_sequence BIGINT NULL,
    ADD COLUMN ended_type VARCHAR(20) NULL,
    ADD COLUMN ended_occurred_at DATETIME(6) NULL,
    ADD COLUMN ended_title VARCHAR(150) NULL,
    ADD COLUMN ended_body VARCHAR(500) NULL,
    ADD COLUMN ended_recipient_user_ids JSON NULL,
    ADD COLUMN live_marker TINYINT GENERATED ALWAYS AS (IF(ended_at IS NULL,1,NULL)) STORED,
    ADD UNIQUE KEY uk_members_live_refrigerator_user (refrigerator_id, user_id, live_marker),
    ADD KEY ix_members_joined_sequence (refrigerator_id, joined_sequence),
    ADD KEY ix_members_ended_sequence (refrigerator_id, ended_sequence),
    DROP INDEX uk_members_refrigerator_user;

CREATE TABLE membership_transmissions (
    membership_transmission_id BIGINT NOT NULL AUTO_INCREMENT,
    refrigerator_id BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (membership_transmission_id),
    UNIQUE KEY uk_membership_transmissions_refrigerator (refrigerator_id),
    CONSTRAINT fk_membership_transmissions_refrigerator FOREIGN KEY (refrigerator_id) REFERENCES refrigerators (refrigerator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE membership_notification_progress (
    membership_notification_progress_id BIGINT NOT NULL AUTO_INCREMENT,
    refrigerator_id BIGINT NOT NULL,
    completed_sequence BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (membership_notification_progress_id),
    UNIQUE KEY uk_membership_notification_progress_refrigerator (refrigerator_id),
    CONSTRAINT fk_membership_notification_progress_refrigerator FOREIGN KEY (refrigerator_id) REFERENCES refrigerators (refrigerator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
