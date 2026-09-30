package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.application.IngredientExpirationService;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.infrastructure.NotificationRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.user.domain.User;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExpirationNotificationService {
    private final RefrigeratorService refrigeratorService;
    private final IngredientExpirationService ingredientExpirationService;
    private final ExpirationNotificationFactory notificationFactory;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientService notificationRecipientService;
    private final Clock clock;

    @Transactional
    public void generate(Long refrigeratorId) {
        List<RefrigeratorMember> members = refrigeratorService.findNotificationRecipients(refrigeratorId);
        if (members.isEmpty()) {
            return;
        }

        Refrigerator refrigerator = members.getFirst().getRefrigerator();
        LocalDate businessDate = BusinessTime.today(clock);

        List<Ingredient> ingredients = ingredientExpirationService.findNotificationTargets(refrigeratorId, businessDate);
        List<Notification> notifications = createNotifications(refrigerator, ingredients, businessDate);

        List<User> recipients = extractRecipients(members);
        saveNotifications(notifications, recipients);
    }

    private List<Notification> createNotifications(Refrigerator refrigerator, List<Ingredient> ingredients, LocalDate businessDate) {
        List<Ingredient> expiringSoonIngredients = new ArrayList<>();
        List<Ingredient> expiredIngredients = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.getExpirationDate().isBefore(businessDate)) {
                expiredIngredients.add(ingredient);
            } else {
                expiringSoonIngredients.add(ingredient);
            }
        }

        List<Notification> notifications = new ArrayList<>();
        if (!expiringSoonIngredients.isEmpty()) {
            notifications.add(notificationFactory.create(refrigerator, expiringSoonIngredients, businessDate));
        }
        if (!expiredIngredients.isEmpty()) {
            notifications.add(notificationFactory.create(refrigerator, expiredIngredients, businessDate));
        }
        return notifications;
    }

    private List<User> extractRecipients(List<RefrigeratorMember> members) {
        return members.stream()
                .map(RefrigeratorMember::getUser)
                .toList();
    }

    private void saveNotifications(List<Notification> notifications, List<User> recipients) {
        for (Notification notification : notifications) {
            Notification savedNotification = notificationRepository.save(notification);
            notificationRecipientService.saveRecipients(savedNotification, recipients);
        }
    }
}
