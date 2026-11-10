-- main 비교: 2026-11-07 종료 시점 524b2095159dcf562812081d3cc5e460ed2939e5 → 2026-11-09 0b8de6e567a86efb891dd30f1301e5cdf54d798f
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_cs;

-- 기존 테이블 4개 변경. 기적용 ALTER는 중복 실행하지 않는다. DDL은 암시적으로 커밋된다.
-- analysis_feedbacks.item_id 값은 제거된다. 구형 피드백을 배출하고 필요한 원본을 보관한 뒤 실행한다.

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

ALTER TABLE cooking_records
    ADD COLUMN recipe_identity TEXT NULL COMMENT 'SOURCE:sourceRecipeId. 도입 전 기록은 NULL',
    ADD COLUMN detail_etag VARCHAR(128) NULL COMMENT '확인한 상세 응답 ETag. 도입 전 기록은 NULL',
    MODIFY COLUMN recipe_name_snapshot VARCHAR(255) NOT NULL COMMENT '적용 당시 표시된 요리명';

ALTER TABLE analysis_feedbacks
    MODIFY COLUMN analysis_feedback_id BIGINT NOT NULL AUTO_INCREMENT
        COMMENT '재고 등록 뒤 AI 분석 하나에 보낼 피드백 묶음',
    ADD COLUMN message_body JSON NULL COMMENT 'Kafka 이벤트 전체. 기존 HTTP 피드백은 NULL',
    DROP COLUMN item_id;

-- 신규 테이블 12개는 최신 정의다. 부분 적용 DB는 원본 migration으로 누락분을 확인하고 CREATE·후속 ALTER를 중복 실행하지 않는다.

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

CREATE TABLE recommendation_outbox (
    recommendation_outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL COMMENT '접수마다 발급하는 ID. 향후 FE jobId로 사용',
    refrigerator_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL COMMENT '동일 요청의 재전송 식별자. 중복 접수는 허용',
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL COMMENT '접수 당시 AI 요청 스냅샷',
    topic VARCHAR(249) NULL,
    message_key VARCHAR(32) NULL,
    message_body JSON NULL COMMENT 'Kafka envelope 원문. HTTP 행은 NULL',
    next_attempt_at DATETIME(6) NULL,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    status VARCHAR(16) NOT NULL COMMENT '메시지 전달 상태. 추천 생성 상태와 분리',
    db_job BOOLEAN NOT NULL DEFAULT FALSE,
    job_snapshot JSON NULL,
    job_expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (recommendation_outbox_id),
    UNIQUE KEY uk_recommendation_outbox_event (event_id),
    KEY ix_recommendation_outbox_request (request_id),
    KEY ix_recommendation_outbox_refrigerator (refrigerator_id),
    KEY ix_recommendation_outbox_pending (status, next_attempt_at),
    KEY ix_recommendation_outbox_lease (status, lease_until),
    CONSTRAINT fk_recommendation_outbox_refrigerator FOREIGN KEY (refrigerator_id) REFERENCES refrigerators (refrigerator_id),
    CONSTRAINT ck_recommendation_outbox_status CHECK (status IN ('PENDING','PUBLISHING','SENT','FAILED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE recommendation_lists (
    recommendation_list_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    refrigerator_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    result_json JSON NOT NULL COMMENT '추천 완료 결과 전체. 레시피 상세·재료·단계·재고 배분 포함',
    request_date DATE NULL COMMENT '추천 접수 시점의 서울 업무 날짜',
    input_key VARCHAR(64) NULL COMMENT '재고 지문과 기본값 적용 옵션의 SHA-256',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (recommendation_list_id),
    UNIQUE KEY uk_recommendation_list_request (request_id),
    KEY ix_recommendation_list_reuse (user_id, refrigerator_id, request_date, input_key, recommendation_list_id),
    CONSTRAINT fk_recommendation_list_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_recommendation_list_refrigerator FOREIGN KEY (refrigerator_id) REFERENCES refrigerators (refrigerator_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE recommendation_states (
    recommendation_state_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    refrigerator_id BIGINT NOT NULL,
    current_list_id BIGINT NULL COMMENT '명시적으로 선택한 목록. 최신 완료 목록과 같을 수 있음',
    latest_list_id BIGINT NULL COMMENT '최신 완료 목록. 완료만으로 current_list_id를 변경하지 않음',
    active_request_id VARCHAR(36) NULL,
    request_date DATE NULL COMMENT '마지막 추천 접수·재사용의 서울 업무 날짜',
    generation_status VARCHAR(16) NULL COMMENT '마지막 생성 작업 상태. 작업이 없으면 NULL',
    generation_trigger VARCHAR(16) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (recommendation_state_id),
    UNIQUE KEY uk_recommendation_state_participant (user_id, refrigerator_id),
    CONSTRAINT fk_recommendation_state_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_recommendation_state_refrigerator FOREIGN KEY (refrigerator_id) REFERENCES refrigerators (refrigerator_id),
    CONSTRAINT fk_recommendation_state_current FOREIGN KEY (current_list_id) REFERENCES recommendation_lists (recommendation_list_id),
    CONSTRAINT fk_recommendation_state_latest FOREIGN KEY (latest_list_id) REFERENCES recommendation_lists (recommendation_list_id),
    CONSTRAINT ck_recommendation_state_status CHECK (generation_status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_recommendation_state_trigger CHECK (generation_trigger IN ('USER','SCHEDULED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

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

CREATE TABLE analysis_job_states (
    analysis_job_id CHAR(36) NOT NULL COMMENT '백엔드 이미지 묶음 작업 ID',
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    job_body JSON NOT NULL COMMENT '분석 작업·이미지·결과 상태 스냅샷',
    expires_at DATETIME(6) NULL COMMENT '진행 중에는 NULL, 종료 후 결과 조회 만료 시각',
    notification_next_attempt_at DATETIME(6) NULL COMMENT 'NULL이면 알림 대기 없음, 선점 중에는 lease 만료 시각',
    notification_lease_token CHAR(36) NULL,
    notification_sse_attempt_count INT NOT NULL DEFAULT 0 COMMENT '전체 완료 SSE 시도 횟수, 최초 포함',
    notification_image_attempt_counts JSON NULL COMMENT 'requestId별 개별 이미지 SSE 시도 횟수',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (analysis_job_id),
    KEY ix_analysis_job_states_user (user_id, created_at),
    KEY ix_analysis_job_states_expiry (expires_at, analysis_job_id),
    KEY ix_analysis_job_states_notification_due (notification_next_attempt_at, analysis_job_id),
    CONSTRAINT fk_analysis_job_states_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT ck_analysis_job_states_status CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','PARTIALLY_COMPLETED','FAILED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE analysis_job_requests (
    analysis_job_request_id BIGINT NOT NULL AUTO_INCREMENT,
    request_id CHAR(36) NOT NULL COMMENT '같은 이미지 재요청 시 여러 작업이 공유하는 멱등 키',
    analysis_job_id CHAR(36) NOT NULL,
    PRIMARY KEY (analysis_job_request_id),
    UNIQUE KEY uk_analysis_job_requests_request_job (request_id, analysis_job_id),
    KEY ix_analysis_job_requests_job (analysis_job_id),
    CONSTRAINT fk_analysis_job_requests_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job_states (analysis_job_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE analysis_outbox (
    analysis_outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    message_id CHAR(36) NOT NULL COMMENT '재발행에도 유지하는 이벤트 ID',
    analysis_job_id CHAR(36) NOT NULL,
    topic VARCHAR(249) NOT NULL,
    message_key VARCHAR(32) NOT NULL COMMENT 'UTF-8 문자열 userId',
    message_body JSON NOT NULL COMMENT 'messageId와 payload를 포함한 고정 발행 본문',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (analysis_outbox_id),
    UNIQUE KEY uk_analysis_outbox_message (message_id),
    KEY ix_analysis_outbox_due (status, next_attempt_at),
    KEY ix_analysis_outbox_lease (status, lease_until),
    KEY ix_analysis_outbox_job (analysis_job_id),
    CONSTRAINT fk_analysis_outbox_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job_states (analysis_job_id),
    CONSTRAINT ck_analysis_outbox_status CHECK (status IN ('PENDING','PUBLISHING','PUBLISHED')),
    CONSTRAINT ck_analysis_outbox_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_analysis_outbox_lease CHECK ((status = 'PUBLISHING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_analysis_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE image_upload_metadata (
    object_key VARBINARY(1024) NOT NULL,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (object_key),
    KEY idx_image_upload_metadata_expires_at (expires_at, object_key)
) ENGINE=InnoDB;

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
