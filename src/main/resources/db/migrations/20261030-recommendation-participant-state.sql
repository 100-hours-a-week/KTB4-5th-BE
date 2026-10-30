-- recommendation_lists·recommendation_states가 없는 기존 DB에 수동 적용한다. 애플리케이션 시작 시 자동 실행하지 않는다.
CREATE TABLE recommendation_lists (
    recommendation_list_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    refrigerator_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    result_json JSON NOT NULL COMMENT '추천 완료 결과 전체. 레시피 상세·재료·단계·재고 배분 포함',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (recommendation_list_id),
    UNIQUE KEY uk_recommendation_list_request (request_id),
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
