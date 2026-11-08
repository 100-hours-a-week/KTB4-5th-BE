-- 기존 DB에 앱 배포 전에 한 번 수동 적용한다. 기존 테이블·데이터는 변경하지 않는다.
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
