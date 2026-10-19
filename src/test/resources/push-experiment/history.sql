-- 누적 환경 전용: 같은 유저·냉장고에 과거 알림 N건, 수신자 N건, ACCEPTED 푸시 2N건을 추가한다.
-- 현재 처리 대상은 seed.sql과 실제 생성 서비스로 준비한다. 과거 행은 규모 비교용 합성 데이터이다.
CREATE TEMPORARY TABLE experiment_history (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL);
INSERT INTO experiment_history
SELECT @offset + 1 + a.n + 10*b.n + 100*c.n + 1000*d.n,
       1 + MOD(@offset + a.n + 10*b.n + 100*c.n + 1000*d.n, @count)
FROM JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]', '$[*]' COLUMNS(n INT PATH '$')) a
CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]', '$[*]' COLUMNS(n INT PATH '$')) b
CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]', '$[*]' COLUMNS(n INT PATH '$')) c
CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]', '$[*]' COLUMNS(n INT PATH '$')) d
WHERE 1 + a.n + 10*b.n + 100*c.n + 1000*d.n <= @batch_count;
INSERT INTO notifications (notification_id, type, title, body, refrigerator_id, created_at, updated_at)
SELECT id, 'EXPIRING', '과거 임박 알림', '과거 재료 · 3일 남았어요', user_id,
       DATE_SUB(@now, INTERVAL 1 DAY), DATE_SUB(@now, INTERVAL 1 DAY) FROM experiment_history;
INSERT INTO notification_recipients (user_id, notification_id)
SELECT user_id, id FROM experiment_history;
INSERT INTO push_notifications (dispatch_key, kind, user_device_id, subscription_version, payload, status,
    attempt_count, next_attempt_at, expires_at, accepted_at, created_at, updated_at, notification_id, user_id, refrigerator_id)
SELECT CONCAT('INBOX:', h.id), 'INBOX', d.user_device_id, d.subscription_version,
    JSON_OBJECT('notificationId', h.id, 'refrigeratorId', h.user_id, 'type', 'EXPIRING', 'title', '과거 임박 알림', 'body', '과거 재료 · 3일 남았어요'),
    'ACCEPTED', 1, NULL, DATE_SUB(DATE(@now) + INTERVAL 12 HOUR, INTERVAL 1 DAY),
    DATE_SUB(@now, INTERVAL 1 DAY), DATE_SUB(@now, INTERVAL 1 DAY), DATE_SUB(@now, INTERVAL 1 DAY), h.id, h.user_id, h.user_id
FROM experiment_history h JOIN user_devices d ON d.user_id = h.user_id;
DROP TEMPORARY TABLE experiment_history;
