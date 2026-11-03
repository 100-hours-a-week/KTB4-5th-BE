-- 기존 recommendation_outbox가 있는 DB에 수동 적용한다. 애플리케이션 시작 시 자동 실행하지 않는다.
-- 콜백·재요청 재사용 확인·생성 중 목록 조회가 request_id로 outbox를 찾을 때 전체 행을 훑지 않게 한다.
ALTER TABLE recommendation_outbox
    ADD KEY ix_recommendation_outbox_request (request_id, recommendation_outbox_id);
