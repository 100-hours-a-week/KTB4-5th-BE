package com.dameokja.backend.ingredient.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum IngredientExceptionCode implements ExceptionCode {

    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "INGREDIENT-400-001", "재료 허용 수량이 아닙니다."),
    MIXED_MEASUREMENT(HttpStatus.BAD_REQUEST, "INGREDIENT-400-002",
            "재료는 개수만 사용하거나 무게만 사용할 수 있습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "INGREDIENT-400-003", "입력 형식이 잘못됐습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "INGREDIENT-404-001", "재고를 찾을 수 없습니다."),
    CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "INGREDIENT-409-001", "냉장고 용량이 가득 찼습니다."),
    MERGE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "INGREDIENT-409-004", "합산 시 허용 수량을 초과합니다."),
    PROHIBITED_NAME(HttpStatus.UNPROCESSABLE_CONTENT, "INGREDIENT-422-001", "부적절한 재고 이름입니다."),
    INVALID_UPDATE_AMOUNT(HttpStatus.UNPROCESSABLE_CONTENT, "INGREDIENT-422-002", "수정할 재고 수량이 유효하지 않습니다."),
    INVALID_UPDATE_MEASUREMENT(HttpStatus.UNPROCESSABLE_CONTENT, "INGREDIENT-422-003", "수정할 재고의 측정값 조합이 유효하지 않습니다."),
    INVALID_UPDATE_VALUE(HttpStatus.UNPROCESSABLE_CONTENT, "INGREDIENT-422-004", "수정할 재고 값이 유효하지 않습니다."),
    STALE_VERSION(HttpStatus.PRECONDITION_FAILED, "INGREDIENT-412-001", "버전 정보가 맞지 않습니다."),
    VERSION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "INGREDIENT-428-001",
            "현재 버전 정보를 If-Match 헤더에 전달해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
