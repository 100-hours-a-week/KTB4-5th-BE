package com.dameokja.backend.ingredient.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum IngredientExceptionCode implements ExceptionCode {

    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "INGREDIENT-400-001", "재료 허용 수량이 아닙니다."),
    MIXED_MEASUREMENT(HttpStatus.BAD_REQUEST, "INGREDIENT-400-002", "재료는 개수만 사용하거나 무게만 사용할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
