package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationCursorTest {
    @Test
    void pageSizeRespectsTheRemainingDisplayLimit() {
        assertThat(NotificationCursor.parse(null).pageSize()).isEqualTo(10);
        assertThat(new NotificationCursor(LocalDateTime.of(2026, 9, 24, 10, 0), 42L, 90)
                .pageSize()).isEqualTo(9);
        assertThat(new NotificationCursor(LocalDateTime.of(2026, 9, 24, 10, 0), 42L, 98)
                .pageSize()).isEqualTo(1);
    }

    @Test
    void inconsistentCursorStateIsRejected() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 24, 10, 0);

        assertThatThrownBy(() -> new NotificationCursor(null, null, -1))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> new NotificationCursor(createdAt, 42L, 0))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> new NotificationCursor(createdAt, null, 10))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> new NotificationCursor(createdAt, 42L, 99))
                .isInstanceOf(CustomException.class);
    }

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
