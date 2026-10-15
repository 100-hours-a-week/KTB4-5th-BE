package com.dameokja.backend.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NotificationChangedTest {
    @Test
    void representsAValidRecipientAndFridge() {
        NotificationChanged changed = new NotificationChanged(1L, 17L);
        assertThat(changed.userId()).isEqualTo(1L);
        assertThat(changed.refrigeratorId()).isEqualTo(17L);
    }

    @ParameterizedTest
    @CsvSource({"NULL,17", "0,17", "-1,17", "1,NULL", "1,0", "1,-1"})
    void invalidTargetsCannotBeConstructed(String userId, String refrigeratorId) {
        Long user = userId.equals("NULL") ? null : Long.valueOf(userId);
        Long fridge = refrigeratorId.equals("NULL") ? null : Long.valueOf(refrigeratorId);
        assertThatThrownBy(() -> new NotificationChanged(user, fridge)).isInstanceOf(IllegalArgumentException.class);
    }
}
