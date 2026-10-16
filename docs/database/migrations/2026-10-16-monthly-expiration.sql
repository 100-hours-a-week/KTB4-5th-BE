-- 검토용. 개발·운영 DB에는 실행하지 않았다.
-- PK/FK/삭제 방식은 변경하지 않는다. 애플리케이션 배포 전에 이 nullable 컬럼이 필요하다.
-- 기존 삭제 기준 expired_count를 정확한 만료 발생 수로 변환하려면 별도 데이터 전환 결정이 필요하다.
-- 이 파일은 기존 카운터를 초기화하거나 과거 삭제 재고의 만료 시점을 추정하지 않는다.
ALTER TABLE refrigerators
    ADD COLUMN expired_counted_on DATE NULL
    COMMENT '이 한국 날짜 00시까지의 만료 반영 완료. 미집계는 NULL';

-- 확인: SHOW COLUMNS FROM refrigerators LIKE 'expired_counted_on';
-- 되돌림: 이전 앱으로 롤백할 때 컬럼은 남겨도 된다.
-- 새 코드의 쓰기 중단 및 집계 데이터 백업·전환 정책 확인 없이 카운터를 되돌리거나 컬럼을 삭제하지 않는다.
