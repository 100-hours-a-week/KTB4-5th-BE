package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationStatus;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PushDispatchQueryTest extends MySqlJpaTest {
    private static final LocalDateTime SEND_AT = LocalDateTime.of(2026, 9, 25, 8, 0);

    @Autowired PushNotificationRepository pushNotificationRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (960001,'수신자','p')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (960001,'냉장고','2026-09')");
        jdbc.update("INSERT INTO refrigerator_members(refrigerator_id,user_id) VALUES (960001,960001)");
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (960001,'EXPIRATION',1)");
        jdbc.update("INSERT INTO user_devices(user_device_id,endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,"
                + "vapid_key_version,created_at,updated_at,user_id) VALUES (960001,'https://push.example.com/960001',"
                + "'p256dh','secret','v1','v1',NOW(6),NOW(6),960001)");
        for (long notificationId = 960001; notificationId <= 960004; notificationId++) {
            jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id) "
                    + "VALUES (?,'EXPIRED','알림','본문',960001)", notificationId);
        }
    }

    @Test
    void findsDueJobsUsableOutsideTransactionAndNextAttemptOfRemainingJobs() {
        assertThat(pushNotificationRepository.findNextAttemptAt()).isEmpty();
        job(960001L, SEND_AT);
        job(960002L, SEND_AT.minusMinutes(5)).fail(SEND_AT.minusMinutes(10));
        job(960003L, SEND_AT).fail(SEND_AT);
        job(960004L, SEND_AT).accept(SEND_AT);
        flushAndClear();

        List<PushNotification> dueJobs = pushNotificationRepository.findDueJobs(SEND_AT);
        entityManager.clear();

        assertThat(dueJobs).extracting(job -> job.getNotification().getId())
                .containsExactlyInAnyOrder(960001L, 960002L);
        assertThat(dueJobs).allSatisfy(job -> {
            assertThat(job.getUserDevice().getSubscriptionVersion()).isEqualTo(1L);
            assertThat(job.getUser().getId()).isEqualTo(960001L);
            assertThat(job.getRefrigerator().getId()).isEqualTo(960001L);
        });
        assertThat(pushNotificationRepository.findNextAttemptAt()).contains(SEND_AT.minusMinutes(10).plusMinutes(5));
    }

    @Test
    void allowsOnlyActiveMemberOfLiveRefrigeratorWithExpirationPushEnabled() {
        assertThat(canReceive()).isTrue();

        jdbc.update("UPDATE notification_preferences SET is_enabled=0 WHERE user_id=960001");
        assertThat(canReceive()).isFalse();
        jdbc.update("UPDATE notification_preferences SET is_enabled=1 WHERE user_id=960001");

        jdbc.update("UPDATE refrigerators SET deleted_at=NOW(6) WHERE refrigerator_id=960001");
        assertThat(canReceive()).isFalse();
        jdbc.update("UPDATE refrigerators SET deleted_at=NULL WHERE refrigerator_id=960001");

        jdbc.update("UPDATE users SET status='WITHDRAWN',deleted_at=NOW(6) WHERE user_id=960001");
        assertThat(canReceive()).isFalse();
        jdbc.update("UPDATE users SET status='ACTIVE',deleted_at=NULL WHERE user_id=960001");

        jdbc.update("DELETE FROM refrigerator_members WHERE user_id=960001");
        assertThat(canReceive()).isFalse();
    }

    @Test
    void findsDueJobsAndExpiredLeasesButExcludesFutureJobsAndActiveLeases() {
        PushNotification pending = job(960001L, SEND_AT);
        PushNotification expiredLease = job(960002L, SEND_AT);
        expiredLease.lease("expired-lease", SEND_AT.minusSeconds(1));
        job(960003L, SEND_AT).lease("active-lease", SEND_AT.plusMinutes(1));
        job(960004L, SEND_AT.plusMinutes(1));
        flushAndClear();

        assertThat(pushNotificationRepository.findLeaseableJobIdsWithSkipLock(SEND_AT, 20))
                .containsExactlyInAnyOrder(pending.getId(), expiredLease.getId());
        assertThat(pushNotificationRepository.findLeaseableJobIdsWithSkipLock(SEND_AT, 1)).hasSize(1);
    }

    @Test
    void includesDueRetriesButExcludesAcceptedJobs() {
        PushNotification retry = job(960001L, SEND_AT.minusMinutes(10));
        retry.fail(SEND_AT.minusMinutes(10));
        job(960002L, SEND_AT).accept(SEND_AT);
        flushAndClear();

        assertThat(pushNotificationRepository.findLeaseableJobIdsWithSkipLock(SEND_AT, 20))
                .containsExactly(retry.getId());
    }

    @Test
    void leasesOnlySelectedJobsAndPreservesAttemptCounts() {
        PushNotification pending = job(960001L, SEND_AT);
        PushNotification retry = job(960002L, SEND_AT.minusMinutes(10));
        retry.fail(SEND_AT.minusMinutes(10));
        PushNotification expiredLease = job(960003L, SEND_AT);
        expiredLease.lease("previous-lease", SEND_AT.minusSeconds(1));
        PushNotification future = job(960004L, SEND_AT.plusMinutes(1));
        flushAndClear();
        List<Long> ids = pushNotificationRepository.findLeaseableJobIdsWithSkipLock(SEND_AT, 3);
        LocalDateTime endAt = SEND_AT.plusMinutes(1);

        int updated = pushNotificationRepository.leasePushJobs(ids, "new-lease", SEND_AT, endAt);
        entityManager.clear();

        assertThat(updated).isEqualTo(3);
        assertThat(ids).containsExactlyInAnyOrder(pending.getId(), retry.getId(), expiredLease.getId());
        assertLease(ids, "new-lease", endAt);
        assertThat(pushNotificationRepository.findById(pending.getId()).orElseThrow().getAttemptCount()).isZero();
        assertThat(pushNotificationRepository.findById(retry.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
        PushNotification untouched = pushNotificationRepository.findById(future.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(PushNotificationStatus.PENDING);
        assertThat(untouched.getLeaseToken()).isNull();
        assertThat(untouched.getLeaseUntil()).isNull();
    }

    @Test
    void reportsZeroUpdatesForMissingJobs() {
        int updated = pushNotificationRepository.leasePushJobs(
                List.of(Long.MAX_VALUE), "new-lease", SEND_AT, SEND_AT.plusMinutes(1));

        assertThat(updated).isZero();
    }

    private void assertLease(List<Long> ids, String token, LocalDateTime endAt) {
        assertThat(pushNotificationRepository.findAllById(ids)).allSatisfy(leased -> {
            assertThat(leased.getStatus()).isEqualTo(PushNotificationStatus.PROCESSING);
            assertThat(leased.getLeaseToken()).isEqualTo(token);
            assertThat(leased.getLeaseUntil()).isEqualTo(endAt);
            assertThat(leased.getUpdatedAt()).isEqualTo(SEND_AT);
        });
    }

    private boolean canReceive() {
        return pushNotificationRepository.canReceiveExpirationPush(960001L, 960001L);
    }

    private PushNotification job(Long notificationId, LocalDateTime createdAt) {
        Notification notification = entityManager.find(Notification.class, notificationId);
        UserDevice device = entityManager.find(UserDevice.class, 960001L);
        return pushNotificationRepository.save(PushNotification.inbox(
                notification, device.getUser(), device, "{}", createdAt, createdAt.withHour(12)));
    }
}
