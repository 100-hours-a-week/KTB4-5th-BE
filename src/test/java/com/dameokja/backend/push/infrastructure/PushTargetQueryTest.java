package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class PushTargetQueryTest extends MySqlJpaTest {
    private static final LocalDateTime TODAY = LocalDateTime.of(2026, 9, 25, 0, 0);
    private static final LocalDateTime SEND_AT = TODAY.withHour(8);

    @Autowired PushNotificationRepository pushNotificationRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES "
                + "(950001,'푸시대상','p'),(950002,'수신끔','p'),(950003,'구독해제','p')");
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key,status,deleted_at) "
                + "VALUES (950004,'탈퇴','p','WITHDRAWN','2026-09-24 10:00:00')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (950001,'냉장고','2026-09')");
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES "
                + "(950001,'EXPIRATION',1),(950002,'EXPIRATION',0),(950003,'EXPIRATION',1),(950004,'EXPIRATION',1)");
        device(950001, 950001, "ACTIVE");
        device(950002, 950001, "ACTIVE");
        device(950003, 950002, "ACTIVE");
        device(950004, 950003, "DISABLED");
        device(950005, 950004, "ACTIVE");
        notification(950001, "EXPIRED", "2026-09-25 04:00:00");
        notification(950002, "EXPIRING", "2026-09-25 04:00:00");
        notification(950003, "MEMBER_JOINED", "2026-09-25 04:00:00");
        notification(950004, "EXPIRED", "2026-09-24 04:00:00");
    }

    @Test
    void findsTodayExpirationNotificationsForEnabledActiveUsersAndActiveDevices() {
        assertThat(findTargets())
                .extracting(target -> target.notification().getId(), target -> target.recipient().getId(),
                        target -> target.device().getId())
                .containsExactly(
                        tuple(950001L, 950001L, 950001L), tuple(950001L, 950001L, 950002L),
                        tuple(950002L, 950001L, 950001L), tuple(950002L, 950001L, 950002L));
    }

    @Test
    void excludesTargetWhoseJobAlreadyExistsForCurrentSubscriptionVersion() {
        Notification notification = entityManager.find(Notification.class, 950001L);
        User recipient = entityManager.find(User.class, 950001L);
        UserDevice device = entityManager.find(UserDevice.class, 950001L);
        pushNotificationRepository.save(PushNotification.inbox(
                notification, recipient, device, "{}", SEND_AT, TODAY.withHour(12)));
        entityManager.flush();

        assertThat(findTargets())
                .extracting(target -> target.notification().getId(), target -> target.device().getId())
                .containsExactly(tuple(950001L, 950002L), tuple(950002L, 950001L), tuple(950002L, 950002L));
    }

    private List<PushInboxTarget> findTargets() {
        return pushNotificationRepository.findExpirationPushTargets(TODAY, TODAY.plusDays(1));
    }

    private void device(long deviceId, long userId, String status) {
        jdbc.update("INSERT INTO user_devices(user_device_id,endpoint,p256dh_key,auth_secret_encrypted,"
                + "encryption_key_version,vapid_key_version,status,created_at,updated_at,user_id) "
                + "VALUES (?,?,'p256dh','secret','v1','v1',?,NOW(6),NOW(6),?)",
                deviceId, "https://push.example.com/" + deviceId, status, userId);
    }

    // 4시 알림 생성 작업이 모든 수신자(푸시 대상 외 사용자 포함)에게 수신 행을 만든 상태를 흉내 낸다.
    private void notification(long notificationId, String type, String createdAt) {
        jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id,created_at,updated_at) "
                + "VALUES (?,?,'알림','본문',950001,?,?)", notificationId, type, createdAt, createdAt);
        jdbc.update("INSERT INTO notification_recipients(notification_id,user_id) VALUES "
                + "(?,950001),(?,950002),(?,950003),(?,950004)",
                notificationId, notificationId, notificationId, notificationId);
    }
}
