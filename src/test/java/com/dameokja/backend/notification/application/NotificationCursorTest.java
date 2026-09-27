package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationCursorTest {
    @Test
    void encodeAndParsePreserveCursorValues() {
        var cursor = new NotificationCursor(LocalDateTime.of(2026, 9, 24, 10, 0), 42L, 10);

        assertThat(NotificationCursor.parse(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> NotificationCursor.parse("not-a-cursor"))
                .isInstanceOf(CustomException.class);
    }
}
