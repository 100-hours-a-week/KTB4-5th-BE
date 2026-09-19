package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class UserConstraintExceptionTranslatorTest {
    @ParameterizedTest
    @CsvSource({
            "uk_users_nickname, NICKNAME_DUPLICATE",
            "users.uk_users_nickname, NICKNAME_DUPLICATE",
            "uk_users_login_id, LOGIN_ID_DUPLICATE",
            "users.uk_users_login_id, LOGIN_ID_DUPLICATE"
    })
    void translatesKnownConstraintsThroughNestedCauses(
            String constraintName, UserExceptionCode expectedCode) {
        var databaseException = new DataIntegrityViolationException("database failure",
                new IllegalStateException(constraintFailure(constraintName)));
        assertThat(UserConstraintExceptionTranslator.translate(databaseException))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(expectedCode));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"other_constraint", "prefix_uk_users_nickname"})
    void preservesUnknownConstraintException(String constraintName) {
        var databaseException = new DataIntegrityViolationException(
                "database failure", constraintFailure(constraintName));
        assertThat(UserConstraintExceptionTranslator.translate(databaseException))
                .isSameAs(databaseException);
    }

    @Test
    void preservesExceptionWithoutConstraintCause() {
        var databaseException = new DataIntegrityViolationException("database failure");
        assertThat(UserConstraintExceptionTranslator.translate(databaseException))
                .isSameAs(databaseException);
    }

    private ConstraintViolationException constraintFailure(String constraintName) {
        return new ConstraintViolationException("constraint failure",
                new SQLException("duplicate", "23000", 1062), constraintName);
    }
}
