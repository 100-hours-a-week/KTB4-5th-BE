package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.domain.NotificationExceptionCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationCursorTest {
    @Test
    void encodeAndParsePreserveCursorValues() {
        NotificationCursor cursor = new NotificationCursor(LocalDateTime.of(2026, 9, 24, 10, 0), 42L, 10);

        assertThat(NotificationCursor.parse(cursor.encode())).isEqualTo(cursor);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingOrBlankCursorStartsAtTheFirstPage(String value) {
        assertThat(NotificationCursor.parse(value)).isEqualTo(new NotificationCursor(null, null, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "42|10|extra", "42|10|", "0|10", "-1|10", "|10"})
    void rejectsMissingOrExtraSegmentsAndNonPositiveIds(String suffix) {
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("2026-09-24T10:00|" + suffix).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> NotificationCursor.parse(cursor)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode").isEqualTo(NotificationExceptionCode.INVALID_CURSOR);
    }

    @Test
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> NotificationCursor.parse("not-a-cursor"))
                .isInstanceOf(CustomException.class);
    }
}
