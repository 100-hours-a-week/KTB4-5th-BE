package com.dameokja.backend.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// @Size와 @Pattern은 문자 수만 셀 수 있어 BCrypt 72바이트 같은 바이트 상한은 이 제약으로 검사한다.
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
public @interface MaxUtf8Bytes {
    int value();

    String message() default "UTF-8 기준 최대 바이트 수를 넘었습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
