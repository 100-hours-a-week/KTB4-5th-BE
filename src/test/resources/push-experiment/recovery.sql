-- 실제 생성 서비스가 만든 작업을 중단 시점의 합성 상태로 바꾼다. PROCESSING은 현행 코드에 없다.
UPDATE push_notifications SET
    status = CASE MOD(notification_id, 7)
        WHEN 0 THEN 'PENDING' WHEN 1 THEN 'RETRY' WHEN 2 THEN 'PENDING'
        WHEN 3 THEN 'RETRY' WHEN 4 THEN 'ACCEPTED' WHEN 5 THEN 'FAILED' ELSE 'CANCELLED' END,
    attempt_count = CASE MOD(notification_id, 7) WHEN 1 THEN 1 WHEN 3 THEN 1 WHEN 4 THEN 1 WHEN 5 THEN 4 ELSE 0 END,
    next_attempt_at = CASE
        WHEN MOD(notification_id, 7) IN (0,1,3) THEN DATE_SUB(@now, INTERVAL 1 MINUTE)
        WHEN MOD(notification_id, 7) = 2 THEN DATE_ADD(@now, INTERVAL 1 HOUR) ELSE NULL END,
    expires_at = CASE WHEN MOD(notification_id, 7) = 3 THEN @now ELSE expires_at END,
    accepted_at = CASE WHEN MOD(notification_id, 7) = 4 THEN @now ELSE NULL END,
    last_error_code = CASE WHEN MOD(notification_id, 7) IN (1,3,5) THEN 'SEND_FAILED'
        WHEN MOD(notification_id, 7) = 6 THEN 'RECIPIENT_UNAVAILABLE' ELSE NULL END
WHERE created_at = @now;
