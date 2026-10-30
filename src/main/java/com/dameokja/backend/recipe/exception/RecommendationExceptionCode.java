package com.dameokja.backend.recipe.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RecommendationExceptionCode implements ExceptionCode {
    GENERATION_IN_PROGRESS(HttpStatus.CONFLICT, "RECOMMEND-409-003", "이미 추천을 생성 중입니다."),
    UNSELECTED_RESULT(HttpStatus.CONFLICT, "RECOMMEND-409-004", "아직 전환하지 않은 추천 결과가 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
