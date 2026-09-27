package com.dameokja.backend.push.application;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationErrorCode;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushDispatchServiceTest {
    private static final LocalDateTime SEND_AT = LocalDateTime.of(2026, 9, 25, 8, 0);
    private static final LocalDateTime DEADLINE = SEND_AT.withHour(12);
    private static final Duration TTL_UNTIL_DEADLINE = Duration.ofHours(4);

    private final PushNotificationRepository pushNotificationRepository = mock(PushNotificationRepository.class);
    private final PushNotificationUpdater pushNotificationUpdater = mock(PushNotificationUpdater.class);
    private final PushSendService pushSendService = mock(PushSendService.class);
    // 서울 2026-09-25 08:00
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T23:00:00Z"), ZoneOffset.UTC);
    private final PushDispatchService service = new PushDispatchService(
            pushNotificationRepository, pushNotificationUpdater, pushSendService, clock, 2);
    private UserDevice device;

    @BeforeEach
    void setUp() {
        User recipient = new User("회원", "default.png");
        ReflectionTestUtils.setField(recipient, "id", 1L);
        device = new UserDevice(recipient, "https://push.example.com/1", "p256dh", "secret".getBytes(), "v1", "v1");
        ReflectionTestUtils.setField(device, "id", 10L);
        when(pushNotificationRepository.canReceiveExpirationPush(1L, 3L)).thenReturn(true);
    }

    @Test
    void sendsEveryDueJobBeyondConcurrencyWithRemainingDeadlineAsTtl() {
        givenDueJobs(job(100L, DEADLINE), job(101L, DEADLINE), job(102L, DEADLINE));
        givenSendResult(PushSendResult.ACCEPTED);
        when(pushNotificationRepository.findNextAttemptAt()).thenReturn(Optional.of(SEND_AT.plusMinutes(5)));

        assertThat(service.dispatchDueJobs()).contains(SEND_AT.plusMinutes(5));

        verify(pushNotificationUpdater).accept(100L, SEND_AT);
        verify(pushNotificationUpdater).accept(101L, SEND_AT);
        verify(pushNotificationUpdater).accept(102L, SEND_AT);
    }

    @Test
    void runsEachDueJobOnItsOwnVirtualThread() {
        givenDueJobs(job(100L, DEADLINE), job(101L, DEADLINE), job(102L, DEADLINE));
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        when(pushSendService.send(10L, "{}", TTL_UNTIL_DEADLINE)).thenAnswer(invocation -> {
            threads.add(Thread.currentThread());
            return PushSendResult.ACCEPTED;
        });

        service.dispatchDueJobs();

        assertThat(threads).hasSize(3).allMatch(Thread::isVirtual);
    }

    @Test
    void limitsConcurrentSendsToConfiguredConcurrency() {
        givenDueJobs(job(100L, DEADLINE), job(101L, DEADLINE), job(102L, DEADLINE), job(103L, DEADLINE));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(pushSendService.send(10L, "{}", TTL_UNTIL_DEADLINE)).thenAnswer(invocation -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.sleep(50);
            inFlight.decrementAndGet();
            return PushSendResult.ACCEPTED;
        });

        service.dispatchDueJobs();

        assertThat(maxInFlight.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void recordsFailureForRetryWhenSendFails() {
        givenDueJobs(job(100L, DEADLINE));
        givenSendResult(PushSendResult.FAILED);

        service.dispatchDueJobs();

        verify(pushNotificationUpdater).fail(100L, SEND_AT);
    }

    @Test
    void recordsFailureForRetryWhenSendThrows() {
        givenDueJobs(job(100L, DEADLINE));
        when(pushSendService.send(10L, "{}", TTL_UNTIL_DEADLINE)).thenThrow(new IllegalStateException("복호화 실패"));

        service.dispatchDueJobs();

        verify(pushNotificationUpdater).fail(100L, SEND_AT);
    }

    @Test
    void cancelsWhenPushServiceReportsSubscriptionUnavailable() {
        givenDueJobs(job(100L, DEADLINE));
        givenSendResult(PushSendResult.UNAVAILABLE);

        service.dispatchDueJobs();

        verify(pushNotificationUpdater).cancel(100L, PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE);
    }

    @Test
    void expiresWithoutSendingAfterDeadline() {
        givenDueJobs(job(100L, SEND_AT));

        service.dispatchDueJobs();

        verify(pushNotificationUpdater).expire(100L);
        verify(pushSendService, never()).send(anyLong(), any(), any());
    }

    @Test
    void cancelsWithoutSendingWhenSubscriptionChangedAfterJobCreation() {
        PushNotification job = job(100L, DEADLINE);
        device.disable();
        givenDueJobs(job);

        service.dispatchDueJobs();

        verify(pushNotificationUpdater).cancel(100L, PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE);
        verify(pushSendService, never()).send(anyLong(), any(), any());
    }

    @Test
    void cancelsWithoutSendingWhenRecipientCanNoLongerReceive() {
        givenDueJobs(job(100L, DEADLINE));
        when(pushNotificationRepository.canReceiveExpirationPush(1L, 3L)).thenReturn(false);

        service.dispatchDueJobs();

        verify(pushNotificationUpdater).cancel(100L, PushNotificationErrorCode.RECIPIENT_UNAVAILABLE);
        verify(pushSendService, never()).send(anyLong(), any(), any());
    }

    private PushNotification job(Long id, LocalDateTime expiresAt) {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 3L);
        Notification notification = mock(Notification.class);
        when(notification.getRefrigerator()).thenReturn(refrigerator);
        PushNotification job = PushNotification.inbox(
                notification, device.getUser(), device, "{}", SEND_AT.minusHours(1), expiresAt);
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }

    private void givenDueJobs(PushNotification... jobs) {
        when(pushNotificationRepository.findDueJobs(SEND_AT)).thenReturn(List.of(jobs));
    }

    private void givenSendResult(PushSendResult result) {
        when(pushSendService.send(10L, "{}", TTL_UNTIL_DEADLINE)).thenReturn(result);
    }
}
