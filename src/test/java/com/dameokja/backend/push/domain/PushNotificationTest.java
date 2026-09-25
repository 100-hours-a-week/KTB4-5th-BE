package com.dameokja.backend.push.domain;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PushNotificationTest {
    private static final LocalDateTime SEND_AT = LocalDateTime.of(2026, 9, 25, 8, 0);
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 9, 25, 12, 0);
    private static final String PAYLOAD = "{\"title\": \"알림\"}";

    private final User recipient = new User("회원", "default.png");
    private final Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
    private final Notification notification = mock(Notification.class);
    private UserDevice device;

    @BeforeEach
    void setUp() {
        when(notification.getId()).thenReturn(7L);
        when(notification.getRefrigerator()).thenReturn(refrigerator);
        device = new UserDevice(recipient, "https://push.example.com/1", "p256dh",
                "encrypted".getBytes(), "enc-v1", "vapid-v1");
        device.disable();
    }

    private PushNotification pending() {
        return PushNotification.inbox(notification, recipient, device, PAYLOAD, SEND_AT, EXPIRES_AT);
    }

    @Test
    void createsPendingInboxJobForNotificationAndSubscriptionVersion() {
        PushNotification job = pending();

        assertThat(job.getDispatchKey()).isEqualTo("INBOX:7");
        assertThat(job.getKind()).isEqualTo(PushNotificationKind.INBOX);
        assertThat(job.getStatus()).isEqualTo(PushNotificationStatus.PENDING);
        assertThat(job.getSubscriptionVersion()).isEqualTo(2L);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getNextAttemptAt()).isEqualTo(SEND_AT);
        assertThat(job.getRefrigerator()).isSameAs(refrigerator);
    }

    @Test
    void acceptsAndStopsScheduling() {
        PushNotification job = pending();

        job.accept(SEND_AT);

        assertThat(job.getStatus()).isEqualTo(PushNotificationStatus.ACCEPTED);
        assertThat(job.getAcceptedAt()).isEqualTo(SEND_AT);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isNull();
    }

    @Test
    void retriesAfterFiveFifteenAndThirtyMinutesThenFails() {
        PushNotification job = pending();
        LocalDateTime failedAt = SEND_AT;

        for (int retryDelayMinutes : new int[] {5, 15, 30}) {
            job.fail(failedAt);
            assertThat(job.getStatus()).isEqualTo(PushNotificationStatus.RETRY);
            assertThat(job.getNextAttemptAt()).isEqualTo(failedAt.plusMinutes(retryDelayMinutes));
            failedAt = job.getNextAttemptAt();
        }
        job.fail(failedAt);

        assertThat(job.getStatus()).isEqualTo(PushNotificationStatus.FAILED);
        assertThat(job.getAttemptCount()).isEqualTo(4);
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getLastErrorCode()).isEqualTo(PushNotificationErrorCode.SEND_FAILED);
    }

    @Test
    void failsWhenNextRetryIsNotBeforeExpiration() {
        PushNotification job = pending();

        job.fail(EXPIRES_AT.minusMinutes(5));

        assertThat(job.getStatus()).isEqualTo(PushNotificationStatus.FAILED);
        assertThat(job.getNextAttemptAt()).isNull();
    }

    @Test
    void cancelsWithReasonWithoutCountingAttempt() {
        PushNotification job = pending();

        job.cancel(PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE);

        assertThat(job.getStatus()).isEqualTo(PushNotificationStatus.CANCELLED);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getLastErrorCode()).isEqualTo(PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE);
    }

    @Test
    void expiresAtDeadline() {
        PushNotification job = pending();

        assertThat(job.isExpired(EXPIRES_AT.minusNanos(1))).isFalse();
        assertThat(job.isExpired(EXPIRES_AT)).isTrue();
        job.expire();

        assertThat(job.getStatus()).isEqualTo(PushNotificationStatus.FAILED);
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getLastErrorCode()).isEqualTo(PushNotificationErrorCode.SEND_WINDOW_EXPIRED);
    }
}
