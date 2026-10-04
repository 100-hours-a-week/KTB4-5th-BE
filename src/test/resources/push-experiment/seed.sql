-- 빈 격리 DB에서만 시작한다. 준비 코드가 @offset/@batch_count와 날짜·테스트 키를 설정한다.
-- user_id <= @notification_targets인 유저만 만료·임박 재고를 가진다. 나머지는 모든 재료가 7일 후 만료된다.
-- 한 배치 최대 10000명. 알림과 푸시 작업은 생성하지 않는다.
CREATE TEMPORARY TABLE experiment_numbers (id BIGINT PRIMARY KEY);
INSERT INTO experiment_numbers
SELECT @offset + 1 + a.n + 10*b.n + 100*c.n + 1000*d.n
FROM JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]', '$[*]' COLUMNS(n INT PATH '$')) a
CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]', '$[*]' COLUMNS(n INT PATH '$')) b
CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]', '$[*]' COLUMNS(n INT PATH '$')) c
CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]', '$[*]' COLUMNS(n INT PATH '$')) d
WHERE 1 + a.n + 10*b.n + 100*c.n + 1000*d.n <= @batch_count;

INSERT INTO users (user_id, nickname, profile_image_key, created_at, updated_at)
SELECT id, CONCAT('실험', id), 'experiment.png', @now, @now FROM experiment_numbers;
INSERT INTO refrigerators (refrigerator_id, name, expired_count_month, created_at, updated_at)
SELECT id, CONCAT('실험냉장고', id), DATE_FORMAT(@now, '%Y-%m'), @now, @now FROM experiment_numbers;
INSERT INTO refrigerator_members (refrigerator_id, user_id, is_active, role, created_at, updated_at)
SELECT id, id, 1, 'OWNER', @now, @now FROM experiment_numbers;
INSERT INTO notification_preferences (user_id, type, is_enabled, created_at, updated_at)
SELECT id, 'EXPIRATION', 1, @now, @now FROM experiment_numbers;
INSERT INTO user_devices (user_device_id, endpoint, p256dh_key, auth_secret_encrypted,
    encryption_key_version, vapid_key_version, subscription_version, status, created_at, updated_at, user_id)
SELECT 2*id-1+device.n, CONCAT('https://fcm.googleapis.com/send/push-experiment/', id, '/', device.label), @public_key, @encrypted_auth,
    'test-v1', 'test-v1', 1, 'ACTIVE', @now, @now, id FROM experiment_numbers
CROSS JOIN JSON_TABLE('[{"n":0,"label":"laptop"},{"n":1,"label":"smartphone"}]', '$[*]'
    COLUMNS(n INT PATH '$.n', label VARCHAR(20) PATH '$.label')) device;
INSERT INTO ingredients (refrigerator_id, name, category, quantity, expiration_date, registration_source, created_at, updated_at)
SELECT id, material.name, 'TOFU_BEAN', 1,
    DATE_ADD(DATE(@now), INTERVAL (CASE WHEN id <= @notification_targets THEN material.days ELSE 7 END) DAY),
    'DIRECT', @now, @now
FROM experiment_numbers
CROSS JOIN JSON_TABLE('[{"name":"만료두부","days":-1},{"name":"오늘두부","days":0},{"name":"임박두부","days":3},{"name":"여유두부","days":7}]', '$[*]'
    COLUMNS(name VARCHAR(100) PATH '$.name', days INT PATH '$.days')) material;
DROP TEMPORARY TABLE experiment_numbers;
