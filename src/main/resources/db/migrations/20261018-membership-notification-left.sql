-- 기존 DB에 애플리케이션 배포 전에 수동 적용한다. 신규 DB는 schema.sql을 사용한다.
-- 사전 확인: SHOW CREATE TABLE notifications;
-- PK, FK, 기존 알림과 수신자, 삭제 정책은 변경하지 않는다.
ALTER TABLE notifications
    DROP CHECK ck_notifications_type,
    ADD CONSTRAINT ck_notifications_type
        CHECK (type IN ('EXPIRED','EXPIRING','MEMBER_JOINED','MEMBER_KICKED','MEMBER_LEFT'));

-- 적용 후 확인: SHOW CREATE TABLE notifications;
-- 롤백: 먼저 알림 생성이 없는 이전 애플리케이션으로 복귀한다.
-- MEMBER_LEFT 행이 있으면 기존 CHECK로 복구할 수 없으므로 데이터를 삭제하지 않고 확장 CHECK를 유지한다.
-- SELECT COUNT(*) FROM notifications WHERE type='MEMBER_LEFT';
