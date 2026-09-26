package com.dameokja.backend.notification.application;

import com.dameokja.backend.push.application.PushDispatchService;
import com.dameokja.backend.push.application.PushNotificationCreationService;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationSchedulerTest {
    private static final LocalDateTime SEND_AT = LocalDateTime.of(2026, 9, 25, 8, 0);

    private final PushNotificationCreationService pushNotificationCreationService =
            mock(PushNotificationCreationService.class);
    private final PushDispatchService pushDispatchService = mock(PushDispatchService.class);
    private final RefrigeratorService refrigeratorService = mock(RefrigeratorService.class);
    private final ExpirationNotificationService expirationNotificationService = mock(ExpirationNotificationService.class);

    @Test
    void runsEveryDayAtEightInSeoul() throws NoSuchMethodException {
        Scheduled scheduled = NotificationScheduler.class.getMethod("runExpirationNotificationBatch").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 8 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    @Test
    void createsJobsThenDispatchesUntilNoJobRemains() {
        when(pushDispatchService.dispatchDueJobs())
                .thenReturn(Optional.of(SEND_AT.minusMinutes(1)), Optional.empty());

        scheduler("2026-09-24T23:00:00Z").runExpirationNotificationBatch();

        InOrder order = inOrder(pushNotificationCreationService, pushDispatchService);
        order.verify(pushNotificationCreationService).createExpirationJobs();
        order.verify(pushDispatchService, times(2)).dispatchDueJobs();
    }

    @Test
    void stopsAtDeadlineEvenIfJobsRemain() {
        when(pushDispatchService.dispatchDueJobs()).thenReturn(Optional.of(SEND_AT.withHour(11)));

        scheduler("2026-09-25T03:00:00Z").runExpirationNotificationBatch();

        verify(pushDispatchService, times(1)).dispatchDueJobs();
    }

    @Test
    void abortsBeforeGenerationAndPushWhenTargetLookupFails() {
        RuntimeException failure = new IllegalStateException("대상 조회 실패");
        when(refrigeratorService.findNotificationTargetRefrigeratorIds()).thenThrow(failure);

        assertThatThrownBy(() -> scheduler("2026-09-24T23:00:00Z").runExpirationNotificationBatch())
                .isSameAs(failure);

        verifyNoInteractions(expirationNotificationService, pushNotificationCreationService, pushDispatchService);
    }

    @Test
    void reachesPushWhenTargetListIsEmpty() {
        when(refrigeratorService.findNotificationTargetRefrigeratorIds()).thenReturn(List.of());
        when(pushDispatchService.dispatchDueJobs()).thenReturn(Optional.empty());

        scheduler("2026-09-24T23:00:00Z").runExpirationNotificationBatch();

        verifyNoInteractions(expirationNotificationService);
        verify(pushNotificationCreationService).createExpirationJobs();
        verify(pushDispatchService).dispatchDueJobs();
    }

    @Test
    void stillGeneratesInboxNotificationsWhenPushIsDisabled() {
        when(refrigeratorService.findNotificationTargetRefrigeratorIds()).thenReturn(List.of(1L, 2L));
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T23:00:00Z"), ZoneOffset.UTC);
        NotificationScheduler scheduler = new NotificationScheduler(pushNotificationCreationService, pushDispatchService,
                refrigeratorService, expirationNotificationService, clock, false);

        scheduler.runExpirationNotificationBatch();

        verify(expirationNotificationService).generate(1L);
        verify(expirationNotificationService).generate(2L);
        verifyNoInteractions(pushNotificationCreationService, pushDispatchService);
    }

    // UTC 시각으로 Clock을 만들어도 기한은 서울 기준 12:00으로 계산해야 한다.
    private NotificationScheduler scheduler(String utcInstant) {
        Clock clock = Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC);
        return new NotificationScheduler(pushNotificationCreationService, pushDispatchService,
                refrigeratorService, expirationNotificationService, clock, true);
    }
}
