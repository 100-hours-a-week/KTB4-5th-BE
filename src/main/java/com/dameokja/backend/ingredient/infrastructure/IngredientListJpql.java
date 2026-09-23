package com.dameokja.backend.ingredient.infrastructure;

/**
 * 재고 목록 정렬별 JPQL. 모든 쿼리는 한 유통기한 그룹(만료 또는 비만료) 안에서만 정렬·커서 조회를 한다.
 * 커서 조건은 "앞 키가 모두 커서와 같고 이번 키가 커서보다 뒤인 행"을 키마다 한 줄씩 OR로 잇는다.
 */
final class IngredientListJpql {

    // 한글로 시작하는 이름 앞에 '0', 그 외에 '1'을 붙여 한글 이름이 먼저 오게 한다.
    // utf8mb4_0900 콜레이션(UCA)에서 한글 자모·음절은 'ㄱ'~'ㅣ' 사이에 모여 있다.
    private static final String NAME_KEY =
            "concat(case when substring(i.name, 1, 1) between 'ㄱ' and 'ㅣ' then '0' else '1' end, i.name)";
    private static final String CURSOR_NAME_KEY =
            "concat(case when substring(:cursorName, 1, 1) between 'ㄱ' and 'ㅣ' then '0' else '1' end, :cursorName)";

    // 유통기한이 expirationFrom 이상, expirationTo 이하인 재고만 조회한다. 경계가 null이면 그쪽 조건이 없고,
    // MySQL은 null 경계 조건을 실행 계획에서 제거한다. 커서가 없으면(첫 페이지) 커서 조건 전체를 생략한다.
    private static final String GROUP_CONDITION = "select i from Ingredient i"
            + " where i.refrigerator.id = :refrigeratorId"
            + " and (:expirationFrom is null or i.expirationDate >= :expirationFrom)"
            + " and (:expirationTo is null or i.expirationDate <= :expirationTo)"
            + " and (:storageType is null or i.storageType = :storageType)"
            + " and (:cursorId is null";

    // 유통기한 오름차순 > 등록일 내림차순 > 이름(한글 우선) > ID
    static final String EXPIRATION_ASC = GROUP_CONDITION
            + " or i.expirationDate > :cursorExpirationDate"
            + " or (i.expirationDate = :cursorExpirationDate and i.createdAt < :cursorCreatedAt)"
            + " or (i.expirationDate = :cursorExpirationDate and i.createdAt = :cursorCreatedAt"
            + "     and " + NAME_KEY + " > " + CURSOR_NAME_KEY + ")"
            + " or (i.expirationDate = :cursorExpirationDate and i.createdAt = :cursorCreatedAt"
            + "     and i.name = :cursorName and i.id > :cursorId))"
            + " order by i.expirationDate, i.createdAt desc, " + NAME_KEY + ", i.id";

    // 등록일 내림차순 > 유통기한 오름차순 > 이름(한글 우선) > ID
    static final String CREATED_DESC = GROUP_CONDITION
            + " or i.createdAt < :cursorCreatedAt"
            + " or (i.createdAt = :cursorCreatedAt and i.expirationDate > :cursorExpirationDate)"
            + " or (i.createdAt = :cursorCreatedAt and i.expirationDate = :cursorExpirationDate"
            + "     and " + NAME_KEY + " > " + CURSOR_NAME_KEY + ")"
            + " or (i.createdAt = :cursorCreatedAt and i.expirationDate = :cursorExpirationDate"
            + "     and i.name = :cursorName and i.id > :cursorId))"
            + " order by i.createdAt desc, i.expirationDate, " + NAME_KEY + ", i.id";

    // 이름(한글 우선) > 유통기한 오름차순 > 등록일 내림차순 > ID
    static final String NAME_ASC = GROUP_CONDITION
            + " or " + NAME_KEY + " > " + CURSOR_NAME_KEY
            + " or (i.name = :cursorName and i.expirationDate > :cursorExpirationDate)"
            + " or (i.name = :cursorName and i.expirationDate = :cursorExpirationDate and i.createdAt < :cursorCreatedAt)"
            + " or (i.name = :cursorName and i.expirationDate = :cursorExpirationDate and i.createdAt = :cursorCreatedAt"
            + "     and i.id > :cursorId))"
            + " order by " + NAME_KEY + ", i.expirationDate, i.createdAt desc, i.id";

    private IngredientListJpql() {
    }
}
