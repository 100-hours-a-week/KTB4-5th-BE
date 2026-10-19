package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpirationNotificationIntegrationTest extends NotificationGenerationTestSupport {
    @ParameterizedTest
    @CsvSource({
            "-10, EXPIRED, 만료된 재료가 있어요, 우유 · 10일 지났어요",
            "-1, EXPIRED, 만료된 재료가 있어요, 우유 · 1일 지났어요",
            "0, EXPIRING, 오늘 만료되는 재료가 있어요, 우유 · 오늘까지예요",
            "1, EXPIRING, 1일 안에 챙길 재료가 있어요, 우유 · 1일 남았어요",
            "2, EXPIRING, 2일 안에 챙길 재료가 있어요, 우유 · 2일 남았어요",
            "3, EXPIRING, 3일 안에 챙길 재료가 있어요, 우유 · 3일 남았어요"
    })
    void classifiesDatesAndBuildsSingleIngredientMessageInSeoul(int remainingDays,
            String type, String title, String body) {
        ingredient(970001L, FRIDGE, "우유", remainingDays);

        generationService.generate(FRIDGE);

        assertThat(messages(FRIDGE)).containsExactly(new Message(type, title, body));
        assertThat(recipientIds(FRIDGE)).containsExactly(USER);
    }

    @Test
    void excludesIngredientsMoreThanThreeDaysAwayAndOtherFridges() {
        ingredient(970001L, FRIDGE, "아직여유", 4);
        refrigerator(970002L);
        ingredient(970002L, 970002L, "다른냉장고", -1);

        generationService.generate(FRIDGE);

        assertThat(messages(FRIDGE)).isEmpty();
        assertThat(recipientIds(FRIDGE)).isEmpty();
    }

    @Test
    void groupsEachTypeAndUsesEarliestDateThenLowestIdAsRepresentative() {
        member(970002L, FRIDGE, true);
        ingredient(970006L, FRIDGE, "최근만료", -1);
        ingredient(970005L, FRIDGE, "두부", 3);
        ingredient(970004L, FRIDGE, "같은날후순위", 0);
        ingredient(970003L, FRIDGE, "우유", 0);
        ingredient(970002L, FRIDGE, "만료후순위", -5);
        ingredient(970001L, FRIDGE, "달걀", -5);

        generationService.generate(FRIDGE);

        assertThat(messages(FRIDGE)).containsExactly(
                new Message("EXPIRING", "3일 안에 챙길 재료가 있어요", "우유 외 2개 · 오늘까지예요"),
                new Message("EXPIRED", "만료된 재료가 있어요", "달걀 외 2개 · 5일 지났어요"));
        assertThat(recipientIds(FRIDGE)).containsExactly(USER, USER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipients "
                + "WHERE user_id IN (970001,970002) AND read_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void skipsFridgeWithoutIngredients() {
        generationService.generate(FRIDGE);

        assertThat(messages(FRIDGE)).isEmpty();
        assertThat(recipientIds(FRIDGE)).isEmpty();
    }

    @Test
    void groupsMessagesByRegistrantWithoutIncludingOtherMembersIngredients() {
        member(970002L, FRIDGE, true);
        member(970003L, FRIDGE, true);
        ingredient(970001L, FRIDGE, "우유", 0);
        ingredient(970002L, FRIDGE, "두부", 3);
        ingredient(970003L, FRIDGE, "김치", -2);
        ingredient(970004L, FRIDGE, "달걀", 1, 970002L);
        ingredient(970005L, FRIDGE, "당근", -1, 970002L);

        generationService.generate(FRIDGE);

        assertThat(messagesFor(USER)).containsExactly(
                new Message("EXPIRING", "3일 안에 챙길 재료가 있어요", "우유 외 1개 · 오늘까지예요"),
                new Message("EXPIRED", "만료된 재료가 있어요", "김치 · 2일 지났어요"));
        assertThat(messagesFor(970002L)).containsExactly(
                new Message("EXPIRING", "1일 안에 챙길 재료가 있어요", "달걀 · 1일 남았어요"),
                new Message("EXPIRED", "만료된 재료가 있어요", "당근 · 1일 지났어요"));
        assertThat(messagesFor(970003L)).isEmpty();
    }

    @Test
    void skipsWhenParticipationEndsAfterTargetLookup() {
        member(970002L, FRIDGE, true);
        ingredient(970001L, FRIDGE, "우유", 0);
        assertThat(refrigeratorService.findNotificationTargetRefrigeratorIds()).contains(FRIDGE);
        jdbc.update("UPDATE refrigerator_members SET is_active=0, ended_at=NOW() WHERE user_id=?", USER);

        generationService.generate(FRIDGE);

        assertThat(messages(FRIDGE)).isEmpty();
        assertThat(recipientIds(FRIDGE)).isEmpty();
    }

    @Test
    void includesUnselectedLiveRecipientsAndExcludesWithdrawnUsers() {
        member(970002L, FRIDGE, true);
        member(970003L, FRIDGE, false);
        member(970004L, FRIDGE, true);
        jdbc.update("UPDATE users SET status='WITHDRAWN',deleted_at=NOW() WHERE user_id=970004");
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (?,'EXPIRATION',0)", USER);
        ingredient(970001L, FRIDGE, "우유", 0);
        ingredient(970002L, FRIDGE, "달걀", 0, 970002L);
        ingredient(970003L, FRIDGE, "두부", -1, 970003L);
        ingredient(970004L, FRIDGE, "탈퇴재고", 0, 970004L);

        generationService.generate(FRIDGE);

        assertThat(recipientIds(FRIDGE)).containsExactly(USER, 970002L, 970003L);
    }

    @Test
    void targetIdsIncludeUnselectedSpacesAndExcludeWithdrawnAndDeletedFridges() {
        member(970002L, FRIDGE, true);
        refrigerator(970002L);
        member(970003L, 970002L, false);
        refrigerator(970003L);
        member(970004L, 970003L, true);
        jdbc.update("UPDATE users SET status='WITHDRAWN',deleted_at=NOW() WHERE user_id=970004");
        refrigerator(970004L);
        member(970005L, 970004L, true);
        jdbc.update("UPDATE refrigerators SET deleted_at=NOW() WHERE refrigerator_id=970004");

        assertThat(refrigeratorService.findNotificationTargetRefrigeratorIds()).containsExactly(FRIDGE, 970002L);
        assertThat(refrigeratorService.findNotificationRecipients(970002L))
                .extracting(member -> member.getUser().getId()).containsExactly(970003L);
        assertThat(refrigeratorService.findNotificationRecipients(970003L)).isEmpty();
        assertThat(refrigeratorService.findNotificationRecipients(970004L)).isEmpty();
    }

    private java.util.List<Message> messagesFor(long userId) {
        return jdbc.query("SELECT n.type,n.title,n.body FROM notifications n JOIN notification_recipients r "
                + "ON r.notification_id=n.notification_id WHERE r.user_id=? ORDER BY n.notification_id",
                (row, index) -> new Message(row.getString("type"), row.getString("title"), row.getString("body")), userId);
    }
}
