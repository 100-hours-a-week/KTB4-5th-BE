-- 재고 같은 품목 UNIQUE 추가 (기존 DB 적용용)
--
-- 대상: schema.sql로 이미 만든 DB. 새 DB는 schema.sql만 적용하면 되고 이 파일은 필요 없다.
-- 같은 품목: 같은 냉장고 안에서 name, expiration_date, storage_type, category, measure_type, weight_unit이 모두 같은 재고.
-- 이미 같은 품목이 여러 행이면 UNIQUE 추가가 실패하므로, 애플리케이션 합산 규칙과 같게 먼저 합친다.
--   - 가장 작은 ingredient_id 행을 남기고 수량·무게만 더한다.
--   - 남는 행의 category, ingredient_image_key, registration_source, created_at은 그대로 둔다.
--   - 합친 값이 상한(COUNT 100, WEIGHT 50000)을 넘으면 CHECK 위반으로 실패한다. 2단계에서 미리 확인한다.
--
-- 실행 전: 애플리케이션을 멈추거나 재고 쓰기가 없는 시간에 실행한다.
-- 단계별로 결과를 확인하며 실행한다. DDL(CREATE TABLE ... AS, ALTER)은 암시적으로 커밋된다.

-- 0. 백업 (합친 행은 되돌릴 수 없으므로 먼저 복사한다)
CREATE TABLE ingredients_backup_20260924 AS SELECT * FROM ingredients;

-- 1. 같은 품목 중복 확인. 결과가 없으면 3단계는 건너뛰어도 된다.
SELECT refrigerator_id, expiration_date, name, storage_type, category, measure_type, weight_unit,
       COUNT(*) AS row_count, MIN(ingredient_id) AS keep_id,
       SUM(quantity) AS total_quantity, SUM(weight_value) AS total_weight
FROM ingredients
GROUP BY refrigerator_id, expiration_date, name, storage_type, category, measure_type, weight_unit
HAVING COUNT(*) > 1;

-- 2. 합치면 상한을 넘는 그룹 확인. 결과가 있으면 3단계를 실행하지 말고 해당 재고를 먼저 수동으로 정리한다.
SELECT refrigerator_id, expiration_date, name, storage_type, category, measure_type, weight_unit,
       SUM(quantity) AS total_quantity, SUM(weight_value) AS total_weight
FROM ingredients
GROUP BY refrigerator_id, expiration_date, name, storage_type, category, measure_type, weight_unit
HAVING COUNT(*) > 1 AND (SUM(quantity) > 100 OR SUM(weight_value) > 50000);

-- 3. 중복 합산 (한 트랜잭션)
-- 이 단계에서 오류(예: Check constraint 'ck_ingredients_measure' is violated)가 나면 ROLLBACK을 실행하고 멈춘다.
START TRANSACTION;

CREATE TEMPORARY TABLE ingredient_same_item_merge AS
SELECT MIN(ingredient_id) AS keep_id,
       refrigerator_id, expiration_date, name, storage_type, category, measure_type, weight_unit,
       SUM(quantity) AS total_quantity, SUM(weight_value) AS total_weight
FROM ingredients
GROUP BY refrigerator_id, expiration_date, name, storage_type, category, measure_type, weight_unit
HAVING COUNT(*) > 1;

-- 삭제될 행의 이미지 키. 값이 있으면 S3 객체 정리 대상으로 기록해 둔다.
SELECT i.ingredient_id, i.ingredient_image_key
FROM ingredients i
JOIN ingredient_same_item_merge m
  ON i.refrigerator_id = m.refrigerator_id AND i.expiration_date = m.expiration_date AND i.name = m.name
 AND i.storage_type = m.storage_type AND i.category = m.category
 AND i.measure_type = m.measure_type AND i.weight_unit = m.weight_unit
WHERE i.ingredient_id <> m.keep_id AND i.ingredient_image_key IS NOT NULL;

-- 남길 행에 합계를 넣는다. updated_at은 애플리케이션 감사 시각과 같게 UTC로 기록한다.
UPDATE ingredients i
JOIN ingredient_same_item_merge m ON i.ingredient_id = m.keep_id
SET i.quantity = m.total_quantity,
    i.weight_value = m.total_weight,
    i.updated_at = UTC_TIMESTAMP(6);

-- 나머지 행을 지운다. ingredients를 참조하는 FK는 없다.
DELETE i
FROM ingredients i
JOIN ingredient_same_item_merge m
  ON i.refrigerator_id = m.refrigerator_id AND i.expiration_date = m.expiration_date AND i.name = m.name
 AND i.storage_type = m.storage_type AND i.category = m.category
 AND i.measure_type = m.measure_type AND i.weight_unit = m.weight_unit
WHERE i.ingredient_id <> m.keep_id;

-- 1단계 쿼리를 다시 실행해 결과가 없는지 확인한 뒤 커밋한다. 이상하면 ROLLBACK.
COMMIT;

DROP TEMPORARY TABLE ingredient_same_item_merge;

-- 4. UNIQUE 추가. 합산 후 중복이 새로 생겼으면 Duplicate entry 오류로 실패하므로 1단계부터 다시 한다.
ALTER TABLE ingredients
    ADD UNIQUE KEY uk_ingredients_same_item
        (refrigerator_id, expiration_date, name, storage_type, category, measure_type, weight_unit),
    ALGORITHM = INPLACE, LOCK = NONE;

-- 5. 확인
SHOW INDEX FROM ingredients WHERE Key_name = 'uk_ingredients_same_item';

-- 6. 확인이 끝나면 백업 테이블을 지운다.
-- DROP TABLE ingredients_backup_20260924;

-- 되돌리기: UNIQUE만 제거한다. 합친 행은 백업 테이블에서 복원한다.
-- ALTER TABLE ingredients DROP INDEX uk_ingredients_same_item;
