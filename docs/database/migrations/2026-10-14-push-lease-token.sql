-- 기존 schema.sql의 claim_token 및 ck_push_claim이 있는 DB 전용이다.
-- 이미 lease_token인 DB에는 실행하지 않는다. 대상 DB와 SHOW CREATE TABLE 결과를 먼저 확인한다.
-- 기존 발송 실행을 종료한 상태에서 스키마를 변경하고 새 애플리케이션을 배포한다.
ALTER TABLE push_notifications
    DROP CHECK ck_push_claim,
    RENAME COLUMN claim_token TO lease_token,
    ADD CONSTRAINT ck_push_claim CHECK (
        status <> 'PROCESSING' OR (lease_until IS NOT NULL AND lease_token IS NOT NULL)
    );
