package com.dameokja.backend.notification.application;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ExpirationNotificationFactory {
    private static final int EXPIRATION_DAY = 0;
    private static final int SINGLE_INGREDIENT_COUNT = 1;

    private static final String EXPIRED_TITLE = "만료된 재료가 있어요";
    private static final String EXPIRING_TODAY_TITLE = "오늘 만료되는 재료가 있어요";
    private static final String EXPIRING_TITLE_TEMPLATE = "%d일 안에 챙길 재료가 있어요";

    private static final String EXPIRED_PERIOD_TEMPLATE = "%d일 지났어요";
    private static final String TODAY_PERIOD = "오늘까지예요";
    private static final String REMAINING_PERIOD_TEMPLATE = "%d일 남았어요";

    private static final String INGREDIENT_SUMMARY_TEMPLATE = "%s 외 %d개";
    private static final String BODY_TEMPLATE = "%s · %s";

    public Notification create(Refrigerator refrigerator,
            List<Ingredient> ingredients, LocalDate businessDate) {
        long remainingDays = ChronoUnit.DAYS.between(businessDate, ingredients.getFirst().getExpirationDate());
        long latestRemainingDays = ChronoUnit.DAYS.between(businessDate, ingredients.getLast().getExpirationDate());
        NotificationType type = remainingDays < EXPIRATION_DAY ? NotificationType.EXPIRED : NotificationType.EXPIRING;

        String title = createTitle(latestRemainingDays);
        String body = BODY_TEMPLATE.formatted(summarizeIngredients(ingredients), describeRemainingDays(remainingDays));

        return new Notification(type, title, body, refrigerator);
    }

    private String createTitle(long latestRemainingDays) {
        if (latestRemainingDays < EXPIRATION_DAY) {
            return EXPIRED_TITLE;
        }
        if (latestRemainingDays == EXPIRATION_DAY) {
            return EXPIRING_TODAY_TITLE;
        }
        return EXPIRING_TITLE_TEMPLATE.formatted(latestRemainingDays);
    }

    private String describeRemainingDays(long remainingDays) {
        if (remainingDays < EXPIRATION_DAY) {
            return EXPIRED_PERIOD_TEMPLATE.formatted(-remainingDays);
        }
        if (remainingDays == EXPIRATION_DAY) {
            return TODAY_PERIOD;
        }
        return REMAINING_PERIOD_TEMPLATE.formatted(remainingDays);
    }

    private String summarizeIngredients(List<Ingredient> ingredients) {
        String representativeName = ingredients.getFirst().getName();
        if (ingredients.size() == SINGLE_INGREDIENT_COUNT) {
            return representativeName;
        }
        return INGREDIENT_SUMMARY_TEMPLATE.formatted(representativeName, ingredients.size() - SINGLE_INGREDIENT_COUNT);
    }
}
