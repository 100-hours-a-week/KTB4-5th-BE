-- 기존 cooking_records가 있는 DB에 수동 적용한다. 애플리케이션 시작 시 자동 실행하지 않는다.
-- 기존 행은 새 컬럼을 NULL로 보존한다. 원래 요청을 확인할 수 없으므로 동일 요청 재시도로 간주하지 않는다.
ALTER TABLE cooking_records
    ADD COLUMN recipe_identity TEXT NULL COMMENT 'SOURCE:sourceRecipeId. 도입 전 기록은 NULL',
    ADD COLUMN detail_etag VARCHAR(128) NULL COMMENT '확인한 상세 응답 ETag. 도입 전 기록은 NULL',
    MODIFY COLUMN recipe_name_snapshot VARCHAR(255) NOT NULL COMMENT '적용 당시 표시된 요리명';
