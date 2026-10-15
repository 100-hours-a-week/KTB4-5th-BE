package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationStatus;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushNotificationRepositoryTest extends MySqlJpaTest {
    private static final LocalDateTime SEND_AT = LocalDateTime.of(2026, 9, 25, 8, 0);
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 9, 25, 12, 0);
    private static final String PAYLOAD = "{\"title\": \"알림\"}";

    @Autowired UserRepository userRepository;
    @Autowired RefrigeratorRepository refrigeratorRepository;
    @Autowired UserDeviceRepository userDeviceRepository;
    @Autowired PushNotificationRepository pushNotificationRepository;
    @Autowired JdbcTemplate jdbc;

    private User recipient;
    private UserDevice device;
    private Notification notification;

    @BeforeEach
    void setUp() {
        recipient = userRepository.save(new User("회원", "default.png"));
        Refrigerator refrigerator = refrigeratorRepository.save(new Refrigerator("냉장고", "2026-09"));
        device = userDeviceRepository.save(new UserDevice(recipient, "https://push.example.com/1",
                "p256dh", "encrypted".getBytes(), "enc-v1", "vapid-v1"));
        entityManager.flush();
        jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id) "
                + "VALUES (940001,'EXPIRED','알림','테스트',?)", refrigerator.getId());
        notification = entityManager.find(Notification.class, 940001L);
    }

    private PushNotification pending() {
        return PushNotification.inbox(notification, recipient, device, PAYLOAD, SEND_AT, EXPIRES_AT);
    }

    @Test
    void savesPendingJobWithJsonPayload() {
        PushNotification saved = pushNotificationRepository.save(pending());
        flushAndClear();

        PushNotification found = pushNotificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getDispatchKey()).isEqualTo("INBOX:940001");
        assertThat(found.getStatus()).isEqualTo(PushNotificationStatus.PENDING);
        assertThat(found.getPayload()).isEqualTo(PAYLOAD);
        assertThat(found.getNotification().getId()).isEqualTo(940001L);
    }

    @Test
    void storesEveryResultStateWithinSchemaConstraints() {
        PushNotification accepted = pending();
        accepted.accept(SEND_AT);
        device.renew("p256dh", "encrypted".getBytes(), "enc-v1", "vapid-v1");
        PushNotification retry = PushNotification.inbox(notification, recipient, device, PAYLOAD, SEND_AT, EXPIRES_AT);
        retry.fail(SEND_AT);

        pushNotificationRepository.save(accepted);
        pushNotificationRepository.save(retry);
        flushAndClear();

        assertThat(pushNotificationRepository.findAll())
                .extracting(PushNotification::getStatus)
                .containsExactlyInAnyOrder(PushNotificationStatus.ACCEPTED, PushNotificationStatus.RETRY);
    }

    @Test
    void rejectsSecondJobForSameNotificationAndSubscriptionVersion() {
        pushNotificationRepository.save(pending());
        entityManager.flush();

        assertThatThrownBy(() -> {
            pushNotificationRepository.save(pending());
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test
    void savesAnalysisWithoutCreatingInboxRows() {
        long notificationsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class);
        long recipientsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipients", Long.class);
        PushNotification saved = pushNotificationRepository.save(analysis());
        flushAndClear();

        PushNotification found = pushNotificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getDispatchKey()).isEqualTo("ANALYSIS:batch-1");
        assertThat(found.getNotification()).isNull();
        assertThat(found.getRefrigerator()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class)).isEqualTo(notificationsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipients", Long.class)).isEqualTo(recipientsBefore);
    }

    @Test
    void rejectsDuplicateAnalysisForSameSubscriptionVersion() {
        pushNotificationRepository.saveAndFlush(analysis());

        assertThatThrownBy(() -> pushNotificationRepository.saveAndFlush(analysis()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void permitsSameAnalysisForAnotherDeviceOrRenewedSubscription() {
        pushNotificationRepository.saveAndFlush(analysis());
        device.renew("p256dh", "encrypted".getBytes(), "enc-v1", "vapid-v1");
        pushNotificationRepository.saveAndFlush(analysis());
        UserDevice secondDevice = userDeviceRepository.save(new UserDevice(recipient, "https://push.example.com/2",
                "p256dh", "encrypted".getBytes(), "enc-v1", "vapid-v1"));
        pushNotificationRepository.saveAndFlush(PushNotification.analysis("batch-1", recipient, secondDevice, PAYLOAD, SEND_AT, EXPIRES_AT));

        assertThat(pushNotificationRepository.count()).isEqualTo(3);
    }

    @Test
    void rejectsAnalysisWithRefrigerator() {
        PushNotification saved = pushNotificationRepository.saveAndFlush(analysis());

        assertThatThrownBy(() -> jdbc.update("UPDATE push_notifications SET refrigerator_id=? WHERE push_notification_id=?",
                notification.getRefrigerator().getId(), saved.getId())).isInstanceOf(UncategorizedSQLException.class).hasMessageContaining("ck_push_refrigerator");
    }

    @Test
    void rejectsAnalysisWithInboxNotification() {
        PushNotification saved = pushNotificationRepository.saveAndFlush(analysis());

        assertThatThrownBy(() -> jdbc.update("UPDATE push_notifications SET notification_id=? WHERE push_notification_id=?",
                notification.getId(), saved.getId())).isInstanceOf(UncategorizedSQLException.class).hasMessageContaining("ck_push_notification");
    }

    @Test
    void rejectsInboxWithoutRefrigerator() {
        PushNotification saved = pushNotificationRepository.saveAndFlush(pending());

        assertThatThrownBy(() -> jdbc.update("UPDATE push_notifications SET refrigerator_id=NULL WHERE push_notification_id=?",
                saved.getId())).isInstanceOf(UncategorizedSQLException.class).hasMessageContaining("ck_push_refrigerator");
    }

    private PushNotification analysis() {
        return PushNotification.analysis("batch-1", recipient, device, PAYLOAD, SEND_AT, EXPIRES_AT);
    }

}
