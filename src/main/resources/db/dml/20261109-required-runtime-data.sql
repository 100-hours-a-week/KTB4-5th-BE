-- 스키마(DDL) 적용 후, 앱/워커를 중지한 관리 세션에서 한 번 실행한다.
-- 카테부 냉장고: 274 / FE 초대코드: 562R2A
-- 정상 소유자가 있으면 재사용한다. 없을 때만 로그인 불가 임시 소유자를 생성한다.
-- 마지막 ktb_seed_status가 OK인지 확인한다. SQL 오류가 나면 COMMIT하지 말고 ROLLBACK한다.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_cs;
SET @ktb_fridge_id = 274;
SET @ktb_invite_code = '562R2A';
SET @ktb_temp_nickname = '카테부임시방장';
SET @seed_now = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 9 HOUR);

START TRANSACTION;

-- HTTP 분석 전달 워커의 전역 잠금 행. 이미 있으면 유지한다.
INSERT INTO analysis_dispatch_gates (gate_id)
VALUES (1)
ON DUPLICATE KEY UPDATE gate_id = 1;

SET @ktb_owner_id = (
    SELECT user_id
    FROM refrigerator_members
    WHERE refrigerator_id = @ktb_fridge_id
      AND role = 'OWNER' AND ended_at IS NULL
    ORDER BY refrigerator_member_id
    LIMIT 1
);

-- 기존 사용자의 소유권·선택 상태·초대코드를 임의로 바꾸지 않는다.
SET @ktb_seed_error = CASE
    WHEN EXISTS (
        SELECT 1 FROM refrigerators
        WHERE refrigerator_id = @ktb_fridge_id
          AND (name <> '카테부' OR deleted_at IS NOT NULL)
    ) THEN '274번이 다른 이름의 냉장고이거나 삭제 상태입니다.'
    WHEN (
        SELECT COUNT(*) FROM refrigerator_members
        WHERE refrigerator_id = @ktb_fridge_id
          AND role = 'OWNER' AND ended_at IS NULL
    ) > 1 THEN '기존 소유자가 여러 명입니다.'
    WHEN @ktb_owner_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM refrigerator_members m
        JOIN users u ON u.user_id = m.user_id
        WHERE m.refrigerator_id = @ktb_fridge_id
          AND m.user_id = @ktb_owner_id
          AND m.role = 'OWNER' AND m.is_active = 1 AND m.ended_at IS NULL
          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
    ) THEN '기존 소유자가 활성 상태로 카테부 냉장고를 선택하고 있지 않습니다.'
    WHEN EXISTS (
        SELECT 1 FROM invite_codes
        WHERE code = @ktb_invite_code AND refrigerator_id <> @ktb_fridge_id
    ) THEN '562R2A 코드가 다른 냉장고에 연결되어 있습니다.'
    WHEN EXISTS (
        SELECT 1 FROM invite_codes i
        WHERE i.code = @ktb_invite_code
          AND i.refrigerator_id = @ktb_fridge_id
          AND i.invite_code_id < (
              SELECT MAX(latest.invite_code_id) FROM invite_codes latest
              WHERE latest.refrigerator_id = @ktb_fridge_id
          )
    ) THEN '562R2A가 해당 냉장고의 최신 코드가 아닙니다.'
    WHEN @ktb_owner_id IS NULL AND EXISTS (
        SELECT 1 FROM users WHERE nickname = @ktb_temp_nickname
    ) THEN '임시 방장 닉네임이 이미 사용 중입니다. 기존 계정을 확인하세요.'
    WHEN @ktb_owner_id IS NULL AND (
        SELECT COUNT(*) FROM refrigerator_members
        WHERE refrigerator_id = @ktb_fridge_id AND ended_at IS NULL
    ) >= 150 THEN '소유자를 추가하면 냉장고 정원 150명을 초과합니다.'
    ELSE NULL
END;
SET @ktb_seed_ok = (@ktb_seed_error IS NULL);

INSERT INTO users (
    nickname, profile_image_key, status, role, cooking_count,
    login_id, password_hash, password_changed_at, deleted_at,
    created_at, updated_at
)
SELECT @ktb_temp_nickname, 'profiles/default.png', 'ACTIVE', 'USER', 0,
       NULL, NULL, NULL, NULL, @seed_now, @seed_now
WHERE @ktb_seed_ok = 1 AND @ktb_owner_id IS NULL;
SET @ktb_owner_id = IF(ROW_COUNT() = 1, LAST_INSERT_ID(), @ktb_owner_id);

INSERT INTO refrigerators (
    refrigerator_id, name, capacity, expired_count, expired_count_month,
    expired_counted_on, created_at, updated_at
)
SELECT @ktb_fridge_id, '카테부', 1500, 0, DATE_FORMAT(@seed_now, '%Y-%m'),
       NULL, @seed_now, @seed_now
WHERE @ktb_seed_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM refrigerators WHERE refrigerator_id = @ktb_fridge_id
);

INSERT INTO refrigerator_members (
    refrigerator_id, user_id, role, is_active, ended_at, created_at, updated_at
)
SELECT @ktb_fridge_id, @ktb_owner_id, 'OWNER', 1, NULL, @seed_now, @seed_now
WHERE @ktb_seed_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM refrigerator_members
    WHERE refrigerator_id = @ktb_fridge_id
      AND user_id = @ktb_owner_id AND ended_at IS NULL
);

INSERT INTO invite_codes (code, refrigerator_id, created_at)
SELECT @ktb_invite_code, @ktb_fridge_id, @seed_now
WHERE @ktb_seed_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM invite_codes WHERE code = @ktb_invite_code
);

-- 가입 시 기본 알림 설정 3종. 이미 있는 수신 설정은 변경하지 않는다.
INSERT INTO notification_preferences (
    user_id, type, is_enabled, created_at, updated_at
)
SELECT @ktb_owner_id, defaults.type, defaults.is_enabled, @seed_now, @seed_now
FROM (
    SELECT 'EXPIRATION' AS type, 1 AS is_enabled
    UNION ALL SELECT 'RECIPE', 0
    UNION ALL SELECT 'ANALYSIS', 1
) defaults
WHERE @ktb_seed_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM notification_preferences existing
    WHERE existing.user_id = @ktb_owner_id AND existing.type = defaults.type
);

COMMIT;

SELECT
    EXISTS (SELECT 1 FROM analysis_dispatch_gates WHERE gate_id = 1) AS analysis_gate_ready,
    IF(@ktb_seed_ok, 'OK', 'SKIPPED') AS ktb_seed_status,
    @ktb_seed_error AS reason,
    @ktb_owner_id AS owner_user_id,
    @ktb_fridge_id AS KTB_REFRIGERATOR_ID,
    @ktb_invite_code AS NEXT_PUBLIC_KAKAOTECH_INVITE_CODE;
