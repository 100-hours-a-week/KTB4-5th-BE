-- 배포 전 별도 검토 후 적용하는 SQL. 애플리케이션 시작 시 자동 실행하지 않는다.
-- 기존 기본 한도 100인 냉장고만 갱신하며 재고 행은 변경하지 않는다.
ALTER TABLE refrigerators ALTER COLUMN capacity SET DEFAULT 1500;
UPDATE refrigerators SET capacity = 1500 WHERE capacity = 100;
