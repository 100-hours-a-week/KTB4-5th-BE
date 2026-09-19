package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

final class UserConstraintExceptionTranslator {
    private UserConstraintExceptionTranslator() {}

    static RuntimeException translate(
            DataIntegrityViolationException dataIntegrityViolationException) {
        Throwable currentCause = dataIntegrityViolationException;
        while (currentCause != null) {
            if (currentCause instanceof ConstraintViolationException constraintViolationException) {
                String constraintName = constraintViolationException.getConstraintName();
                if (matchesConstraint(constraintName, "uk_users_nickname")) {
                    return new CustomException(UserExceptionCode.NICKNAME_DUPLICATE);
                }
                if (matchesConstraint(constraintName, "uk_users_login_id")) {
                    return new CustomException(UserExceptionCode.LOGIN_ID_DUPLICATE);
                }
            }
            currentCause = currentCause.getCause();
        }
        return dataIntegrityViolationException;
    }

    private static boolean matchesConstraint(String actualConstraintName,
            String expectedConstraintName) {
        return actualConstraintName != null
                && (actualConstraintName.equals(expectedConstraintName)
                || actualConstraintName.endsWith("." + expectedConstraintName));
    }
}
