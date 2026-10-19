package com.dameokja.backend.refrigerator.domain;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.REFRIGERATOR_DELETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RefrigeratorAvailabilityTest {
    @Test
    void availableRefrigeratorPassesValidation() {
        Refrigerator refrigerator = new Refrigerator("home", "2026-10");
        assertThatCode(refrigerator::validateAvailable).doesNotThrowAnyException();
    }

    @Test
    void deletedRefrigeratorIsRejectedWithoutChangingDeletionTime() {
        Refrigerator refrigerator = new Refrigerator("home", "2026-10");
        LocalDateTime deletedAt = LocalDateTime.of(2026, 10, 15, 12, 0);
        refrigerator.delete(deletedAt);

        assertThatThrownBy(refrigerator::validateAvailable).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(REFRIGERATOR_DELETED));
        assertThat(refrigerator.getDeletedAt()).isEqualTo(deletedAt);
    }
}
