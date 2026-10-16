package com.dameokja.backend.refrigerator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MonthlyExpirationTest {
    @Test
    void monthStartsWithPreviousMonthsLastExpirationDate() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
        LocalDate october = LocalDate.of(2026, 10, 1);

        assertThat(refrigerator.unaccountedExpirationFrom(october)).isEqualTo(october.minusDays(1));
        refrigerator.recordExpirations(october, 2);
        assertThat(refrigerator.monthlyExpiredCount(october)).isEqualTo(2);
        assertThat(refrigerator.unaccountedExpirationFrom(october)).isEqualTo(october);
    }

    @Test
    void advancesOncePerBusinessDateAndResetsNextMonth() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-10");
        LocalDate today = LocalDate.of(2026, 10, 16);
        refrigerator.recordExpirations(today, 3);
        refrigerator.recordExpirations(today, 3);
        assertThat(refrigerator.monthlyExpiredCount(today)).isEqualTo(3);
        assertThat(refrigerator.monthlyExpiredCount(LocalDate.of(2026, 11, 1))).isZero();

        refrigerator.recordExpirations(LocalDate.of(2026, 11, 1), 1);
        assertThat(refrigerator.getExpiredCount()).isOne();
        assertThat(refrigerator.getExpiredCountMonth()).isEqualTo("2026-11");
    }

    @Test
    void rejectsNegativeCountWithoutChangingRecordedValues() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-10");
        LocalDate today = LocalDate.of(2026, 10, 16);
        refrigerator.recordExpirations(today, 3);

        assertThatThrownBy(() -> refrigerator.recordExpirations(today.plusDays(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(refrigerator.getExpiredCount()).isEqualTo(3);
        assertThat(refrigerator.getExpiredCountedOn()).isEqualTo(today);
    }

    @Test
    void keepsLegacyDeletionCountingUntilBatchIsConnected() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-10");

        refrigerator.countExpiredDeletion();
        refrigerator.countExpiredDeletions(2);

        assertThat(refrigerator.getExpiredCount()).isEqualTo(3);
        assertThat(refrigerator.getExpiredCountedOn()).isNull();
    }
}
