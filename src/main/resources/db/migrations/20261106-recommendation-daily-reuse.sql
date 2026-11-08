-- 기존 DB에 수동 적용한다. 애플리케이션 시작 시 자동 실행하지 않는다.
ALTER TABLE recommendation_lists
    ADD COLUMN request_date DATE NULL,
    ADD COLUMN input_key VARCHAR(64) NULL,
    ADD KEY ix_recommendation_list_reuse (user_id, refrigerator_id, request_date, input_key, recommendation_list_id);
UPDATE recommendation_lists list
LEFT JOIN (SELECT request_id, MIN(created_at) AS accepted_at FROM recommendation_outbox GROUP BY request_id) request ON request.request_id = list.request_id
SET list.request_date = COALESCE(DATE(request.accepted_at), DATE(list.created_at));
ALTER TABLE recommendation_states ADD COLUMN request_date DATE NULL;
UPDATE recommendation_states state
LEFT JOIN recommendation_lists latest ON latest.recommendation_list_id = state.latest_list_id
LEFT JOIN (
    SELECT request_id, MIN(created_at) AS accepted_at FROM recommendation_outbox GROUP BY request_id
) request ON request.request_id = COALESCE(state.active_request_id, latest.request_id)
SET state.request_date = COALESCE(DATE(request.accepted_at), latest.request_date, DATE(state.updated_at));
-- 기존 완료 목록은 입력 키를 추측하지 않고 당일 재사용 대상에서 제외한다.
