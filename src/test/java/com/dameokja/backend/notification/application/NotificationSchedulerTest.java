package com.dameokja.backend.notification.application;

import com.dameokja.backend.push.application.PushNotificationCreationService;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationSchedulerTest {
    private final PushNotificationCreationService pushNotificationCreationService =
            mock(PushNotificationCreationService.class);
    private final RefrigeratorService refrigeratorService = mock(RefrigeratorService.class);
    private final ExpirationNotificationService expirationNotificationService = mock(ExpirationNotificationService.class);
    private final NotificationRecipientService notificationRecipientService = mock(NotificationRecipientService.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    @Test
    void runsEveryDayAtEightInSeoul() throws NoSuchMethodException {
        Scheduled scheduled = NotificationScheduler.class.getMethod("runExpirationNotificationBatch").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${notification.expiration-batch.cron:0 0 8 * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    @Test
    void createsPushJobsAfterInboxGenerationAndReturns() {
        when(refrigeratorService.findNotificationTargetRefrigeratorIds()).thenReturn(List.of(1L, 2L));

        scheduler("2026-09-24T23:00:00Z").runExpirationNotificationBatch();

        InOrder order = inOrder(expirationNotificationService, pushNotificationCreationService);
        order.verify(expirationNotificationService).generate(1L);
        order.verify(expirationNotificationService).generate(2L);
        order.verify(pushNotificationCreationService).createExpirationJobs();
    }

    @Test
    void abortsBeforeGenerationAndPushWhenTargetLookupFails() {
        RuntimeException failure = new IllegalStateException("대상 조회 실패");
        when(refrigeratorService.findNotificationTargetRefrigeratorIds()).thenThrow(failure);

        assertThatThrownBy(() -> scheduler("2026-09-24T23:00:00Z").runExpirationNotificationBatch())
                .isSameAs(failure);

        verifyNoInteractions(expirationNotificationService, pushNotificationCreationService);
    }

    @Test
    void reachesPushWhenTargetListIsEmpty() {
        when(refrigeratorService.findNotificationTargetRefrigeratorIds()).thenReturn(List.of());

        scheduler("2026-09-24T23:00:00Z").runExpirationNotificationBatch();

        verifyNoInteractions(expirationNotificationService);
        verify(pushNotificationCreationService).createExpirationJobs();
    }

    @Test
    void stillGeneratesInboxNotificationsWhenPushIsDisabled() {
        when(refrigeratorService.findNotificationTargetRefrigeratorIds()).thenReturn(List.of(1L, 2L));
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T23:00:00Z"), ZoneOffset.UTC);
        NotificationScheduler scheduler = new NotificationScheduler(pushNotificationCreationService,
                refrigeratorService, expirationNotificationService, notificationRecipientService, notificationService, clock, false);

        scheduler.runExpirationNotificationBatch();

        verify(expirationNotificationService).generate(1L);
        verify(expirationNotificationService).generate(2L);
        verifyNoInteractions(pushNotificationCreationService);
    }

    @Test
    void cleansUpAtThreeInSeoulEvenWhenPushIsDisabled() throws NoSuchMethodException {
        Scheduled scheduled = NotificationScheduler.class.getMethod("runNotificationCleanup").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 3 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T18:00:00Z"), ZoneOffset.UTC);
        NotificationScheduler scheduler = new NotificationScheduler(pushNotificationCreationService,
                refrigeratorService, expirationNotificationService, notificationRecipientService, notificationService, clock, false);

        scheduler.runNotificationCleanup();

        InOrder order = inOrder(notificationRecipientService, notificationService);
        order.verify(notificationRecipientService).deleteExcessRecipients();
        order.verify(notificationService).deleteUnreferencedNotifications();
        verifyNoInteractions(pushNotificationCreationService, refrigeratorService, expirationNotificationService);
    }

    @Test
    void stopsBeforeBodyCleanupWhenRecipientCleanupFails() {
        RuntimeException failure = new IllegalStateException("수신자 정리 실패");
        when(notificationRecipientService.deleteExcessRecipients()).thenThrow(failure);

        assertThatThrownBy(() -> scheduler("2026-09-24T18:00:00Z").runNotificationCleanup()).isSameAs(failure);

        verifyNoInteractions(notificationService, pushNotificationCreationService);
    }

    // UTC Clock을 주입해도 배치 내부 시각은 서울 기준으로 계산한다.
    private NotificationScheduler scheduler(String utcInstant) {
        Clock clock = Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC);
        return new NotificationScheduler(pushNotificationCreationService,
                refrigeratorService, expirationNotificationService, notificationRecipientService, notificationService, clock, true);
    }
}
