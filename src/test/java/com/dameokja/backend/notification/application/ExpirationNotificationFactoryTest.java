package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpirationNotificationFactoryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private final ExpirationNotificationFactory factory = new ExpirationNotificationFactory();
    private final Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");

    @ParameterizedTest
    @CsvSource({
            "-1, EXPIRED, 만료된 재료가 있어요, 우유 · 1일 지났어요",
            "0, EXPIRING, 오늘 만료되는 재료가 있어요, 우유 · 오늘까지예요",
            "1, EXPIRING, 1일 안에 챙길 재료가 있어요, 우유 · 1일 남았어요",
            "3, EXPIRING, 3일 안에 챙길 재료가 있어요, 우유 · 3일 남았어요"
    })
    void createsUnsavedNotificationWithSingleIngredient(int days,
            NotificationType type, String title, String body) {
        Notification notification = factory.create(refrigerator, List.of(ingredient("우유", days)), TODAY);

        assertThat(notification.getId()).isNull();
        assertThat(notification.getRefrigerator()).isSameAs(refrigerator);
        assertThat(notification.getType()).isEqualTo(type);
        assertThat(notification.getTitle()).isEqualTo(title);
        assertThat(notification.getBody()).isEqualTo(body);
    }

    @Test
    void usesLatestDateForTitleAndRepresentativeDateForBody() {
        List<Ingredient> ingredients = List.of(ingredient("우유", 0), ingredient("두부", 3));

        Notification notification = factory.create(refrigerator, ingredients, TODAY);

        assertThat(notification.getTitle()).isEqualTo("3일 안에 챙길 재료가 있어요");
        assertThat(notification.getBody()).isEqualTo("우유 외 1개 · 오늘까지예요");
    }

    @Test
    void usesOldestExpiredIngredientForBody() {
        List<Ingredient> ingredients = List.of(ingredient("우유", -5), ingredient("두부", -1));

        Notification notification = factory.create(refrigerator, ingredients, TODAY);

        assertThat(notification.getType()).isEqualTo(NotificationType.EXPIRED);
        assertThat(notification.getTitle()).isEqualTo("만료된 재료가 있어요");
        assertThat(notification.getBody()).isEqualTo("우유 외 1개 · 5일 지났어요");
    }

    private Ingredient ingredient(String name, int remainingDays) {
        Ingredient ingredient = mock(Ingredient.class);
        when(ingredient.getName()).thenReturn(name);
        when(ingredient.getExpirationDate()).thenReturn(TODAY.plusDays(remainingDays));
        return ingredient;
    }
}
