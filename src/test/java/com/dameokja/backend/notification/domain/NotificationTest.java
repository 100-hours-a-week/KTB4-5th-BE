package com.dameokja.backend.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationTest {
    private final Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");

    @ParameterizedTest
    @ValueSource(strings = {"type", "title", "body", "refrigerator"})
    void rejectsNullRequiredField(String field) {
        assertThatThrownBy(() -> new Notification(
                field.equals("type") ? null : NotificationType.EXPIRING,
                field.equals("title") ? null : "제목",
                field.equals("body") ? null : "본문",
                field.equals("refrigerator") ? null : refrigerator))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "🥛"})
    void acceptsTextAtColumnLimitsIncludingSupplementaryUnicodeCharacters(String character) {
        String title = character.repeat(150);
        String body = character.repeat(500);

        Notification notification = new Notification(NotificationType.EXPIRING, title, body, refrigerator);

        assertThat(notification.getType()).isEqualTo(NotificationType.EXPIRING);
        assertThat(notification.getTitle()).isEqualTo(title);
        assertThat(notification.getBody()).isEqualTo(body);
        assertThat(notification.getRefrigerator()).isSameAs(refrigerator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "🥛"})
    void rejectsTitleOverColumnLimit(String character) {
        assertThatThrownBy(() -> new Notification(NotificationType.EXPIRING,
                character.repeat(151), "본문", refrigerator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "🥛"})
    void rejectsBodyOverColumnLimit(String character) {
        assertThatThrownBy(() -> new Notification(NotificationType.EXPIRING,
                "제목", character.repeat(501), refrigerator))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
