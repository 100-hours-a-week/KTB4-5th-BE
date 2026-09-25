package com.dameokja.backend.push.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpirationPushSchedulerTest {
    private static final LocalDateTime SEND_AT = LocalDateTime.of(2026, 9, 25, 8, 0);

    private final PushNotificationCreationService pushNotificationCreationService =
            mock(PushNotificationCreationService.class);
    private final PushDispatchService pushDispatchService = mock(PushDispatchService.class);

    @Test
    void runsEveryDayAtEightInSeoul() throws NoSuchMethodException {
        Scheduled scheduled = ExpirationPushScheduler.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 8 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    @Test
    void createsJobsThenDispatchesUntilNoJobRemains() {
        when(pushDispatchService.dispatchDueJobs())
                .thenReturn(Optional.of(SEND_AT.minusMinutes(1)), Optional.empty());

        scheduler("2026-09-24T23:00:00Z").run();

        InOrder order = inOrder(pushNotificationCreationService, pushDispatchService);
        order.verify(pushNotificationCreationService).createExpirationJobs();
        order.verify(pushDispatchService, times(2)).dispatchDueJobs();
    }

    @Test
    void stopsAtDeadlineEvenIfJobsRemain() {
        when(pushDispatchService.dispatchDueJobs()).thenReturn(Optional.of(SEND_AT.withHour(11)));

        scheduler("2026-09-25T03:00:00Z").run();

        verify(pushDispatchService, times(1)).dispatchDueJobs();
    }

    // UTC 시각으로 Clock을 만들어도 기한은 서울 기준 12:00으로 계산해야 한다.
    private ExpirationPushScheduler scheduler(String utcInstant) {
        Clock clock = Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC);
        return new ExpirationPushScheduler(pushNotificationCreationService, pushDispatchService, clock);
    }
}
