package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.ingredient.application.ExpiredIngredientCountService;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Import({NotificationRecipientService.class, NotificationService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationCleanupIntegrationTest extends MySqlJpaTest {
    private static final long USER = 980001L;
    private static final long OTHER_USER = 980002L;
    private static final long FRIDGE = 980001L;
    private static final long OTHER_FRIDGE = 980002L;
    private static final long FIRST_NOTIFICATION = 980001L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 1, 8, 0);

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRecipientService recipientService;
    @Autowired NotificationService notificationService;
    @MockitoBean RefrigeratorAccessService refrigeratorAccessService;
    @MockitoBean ExpiredIngredientCountService expiredIngredientCountService;

    @BeforeEach
    void seedUsersAndFridges() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (?, '회원', 'test'), (?, '다른 회원', 'test')",
                USER, OTHER_USER);
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) "
                + "VALUES (?, '냉장고', '2026-09'), (?, '다른 냉장고', '2026-09')", FRIDGE, OTHER_FRIDGE);
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_cleanup_recipient");
        jdbc.update("DELETE FROM push_notifications WHERE user_id IN (?,?)", USER, OTHER_USER);
        jdbc.update("DELETE FROM user_devices WHERE user_id IN (?,?)", USER, OTHER_USER);
        jdbc.update("DELETE FROM notification_recipients WHERE user_id IN (?,?)", USER, OTHER_USER);
        jdbc.update("DELETE FROM notifications WHERE refrigerator_id IN (?,?)", FRIDGE, OTHER_FRIDGE);
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id IN (?,?)", FRIDGE, OTHER_FRIDGE);
        jdbc.update("DELETE FROM users WHERE user_id IN (?,?)", USER, OTHER_USER);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 98, 99})
    void keepsAllRecipientsWithinLimit(int count) {
        notifications(FRIDGE, FIRST_NOTIFICATION, count);

        assertThat(recipientService.deleteExcessRecipients()).isZero();
        assertThat(notificationService.deleteUnreferencedNotifications()).isZero();
        assertThat(remainingIds(USER, FRIDGE)).hasSize(count);
    }

    @Test
    void removesNewerReadNotificationsBeforeOlderUnreadNotifications() {
        notifications(FRIDGE, FIRST_NOTIFICATION, 101);
        markRead(FIRST_NOTIFICATION + 99, FIRST_NOTIFICATION + 100);

        assertThat(recipientService.deleteExcessRecipients()).isEqualTo(2);

        assertThat(remainingIds(USER, FRIDGE)).hasSize(99)
                .contains(FIRST_NOTIFICATION)
                .doesNotContain(FIRST_NOTIFICATION + 99, FIRST_NOTIFICATION + 100);
    }

    @Test
    void removesOldestUnreadNotificationsWhenReadNotificationsAreInsufficient() {
        notifications(FRIDGE, FIRST_NOTIFICATION, 103);
        markRead(FIRST_NOTIFICATION + 102, FIRST_NOTIFICATION + 102);

        assertThat(recipientService.deleteExcessRecipients()).isEqualTo(4);

        assertThat(remainingIds(USER, FRIDGE)).hasSize(99)
                .doesNotContain(FIRST_NOTIFICATION, FIRST_NOTIFICATION + 1,
                        FIRST_NOTIFICATION + 2, FIRST_NOTIFICATION + 102);
    }

    @Test
    void usesCreationTimeThenIdWithinReadNotifications() {
        notifications(FRIDGE, FIRST_NOTIFICATION, 101);
        markRead(FIRST_NOTIFICATION, FIRST_NOTIFICATION + 100);
        jdbc.update("UPDATE notifications SET created_at=? WHERE refrigerator_id=?", CREATED_AT, FRIDGE);
        jdbc.update("UPDATE notifications SET created_at=? WHERE notification_id=?",
                CREATED_AT.minusDays(1), FIRST_NOTIFICATION + 100);

        assertThat(recipientService.deleteExcessRecipients()).isEqualTo(2);

        assertThat(remainingIds(USER, FRIDGE)).hasSize(99)
                .doesNotContain(FIRST_NOTIFICATION, FIRST_NOTIFICATION + 100);
    }

    @Test
    void countsEachUserAndFridgeSeparatelyAndKeepsSharedBodies() {
        notifications(FRIDGE, FIRST_NOTIFICATION, 100);
        notifications(OTHER_FRIDGE, 981001L, 99);
        jdbc.update("INSERT INTO notification_recipients(user_id,notification_id) VALUES (?,?)", OTHER_USER, FIRST_NOTIFICATION);

        assertThat(recipientService.deleteExcessRecipients()).isEqualTo(1);
        assertThat(notificationService.deleteUnreferencedNotifications()).isZero();

        assertThat(remainingIds(USER, FRIDGE)).hasSize(99).doesNotContain(FIRST_NOTIFICATION);
        assertThat(remainingIds(USER, OTHER_FRIDGE)).hasSize(99);
        assertThat(remainingIds(OTHER_USER, FRIDGE)).containsExactly(FIRST_NOTIFICATION);
    }

    @Test
    void deletesOrphanBodiesButPreservesBodiesReferencedByPushAndCanRunAgain() {
        notifications(FRIDGE, FIRST_NOTIFICATION, 101);
        pushReferencing(FIRST_NOTIFICATION);

        assertThat(recipientService.deleteExcessRecipients()).isEqualTo(2);
        assertThat(notificationService.deleteUnreferencedNotifications()).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT notification_id FROM notifications WHERE refrigerator_id=?",
                Long.class, FRIDGE)).hasSize(100).contains(FIRST_NOTIFICATION).doesNotContain(FIRST_NOTIFICATION + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE notification_id=?",
                Integer.class, FIRST_NOTIFICATION)).isEqualTo(1);
        assertThat(recipientService.deleteExcessRecipients()).isZero();
        assertThat(notificationService.deleteUnreferencedNotifications()).isZero();
    }

    @Test
    void rollsBackRecipientDeletionWhenDatabaseRejectsDeletion() {
        notifications(FRIDGE, FIRST_NOTIFICATION, 101);
        jdbc.execute("CREATE TRIGGER fail_cleanup_recipient BEFORE DELETE ON notification_recipients "
                + "FOR EACH ROW BEGIN IF OLD.notification_id=" + (FIRST_NOTIFICATION + 1)
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cleanup failure'; END IF; END");

        assertThatThrownBy(() -> recipientService.deleteExcessRecipients())
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("cleanup failure");

        assertThat(remainingIds(USER, FRIDGE)).hasSize(101);
    }

    private void notifications(long fridgeId, long firstId, int count) {
        for (int index = 0; index < count; index++) {
            jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id,created_at) "
                    + "VALUES (?,'EXPIRED','제목','본문',?,?)", firstId + index, fridgeId, CREATED_AT.plusMinutes(index));
            jdbc.update("INSERT INTO notification_recipients(user_id,notification_id) VALUES (?,?)", USER, firstId + index);
        }
    }

    private void markRead(long firstId, long lastId) {
        jdbc.update("UPDATE notification_recipients SET read_at=? WHERE notification_id BETWEEN ? AND ?",
                CREATED_AT.plusDays(1), firstId, lastId);
    }

    private List<Long> remainingIds(long userId, long fridgeId) {
        return jdbc.queryForList("SELECT r.notification_id FROM notification_recipients r JOIN notifications n "
                + "ON n.notification_id=r.notification_id WHERE r.user_id=? AND n.refrigerator_id=? ORDER BY r.notification_id",
                Long.class, userId, fridgeId);
    }

    private void pushReferencing(long notificationId) {
        jdbc.update("INSERT INTO user_devices(user_device_id,endpoint,p256dh_key,auth_secret_encrypted,"
                + "encryption_key_version,vapid_key_version,created_at,updated_at,user_id) "
                + "VALUES (?, 'https://push.example.com/cleanup', 'key', X'01', 'v1', 'v1', ?, ?, ?)", USER, CREATED_AT, CREATED_AT, USER);
        jdbc.update("INSERT INTO push_notifications(dispatch_key,kind,user_device_id,subscription_version,payload,"
                + "status,expires_at,created_at,updated_at,notification_id,user_id,refrigerator_id) "
                + "VALUES (?, 'INBOX', ?, 1, '{}', 'FAILED', ?, ?, ?, ?, ?, ?)",
                "INBOX:" + notificationId, USER, CREATED_AT.plusHours(4), CREATED_AT, CREATED_AT, notificationId, USER, FRIDGE);
    }
}
