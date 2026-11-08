-- 기존 HTTP 피드백의 본문·상태·선점 정보는 변경하지 않는다. 수동 적용하며 자동 실행하지 않는다.
ALTER TABLE analysis_feedbacks
    ADD COLUMN message_body JSON NULL COMMENT 'Kafka 이벤트 전체. 기존 HTTP 피드백은 NULL';
