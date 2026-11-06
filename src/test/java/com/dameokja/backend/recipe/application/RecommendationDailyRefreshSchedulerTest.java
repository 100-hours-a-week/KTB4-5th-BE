package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import com.dameokja.backend.global.config.SchedulingConfig;
import com.dameokja.backend.support.ServiceIntegrationTest.TestClock;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.scheduling.annotation.Scheduled;

class RecommendationDailyRefreshSchedulerTest {
    @Test
    void catchesUpAfterSevenAndRegistersAgainOnlyOnTheNextSeoulDate() {
        TestClock clock = new TestClock();
        RecommendationDailyRefreshService refresh = mock(RecommendationDailyRefreshService.class);
        RecommendationDailyRefreshScheduler scheduler = new RecommendationDailyRefreshScheduler(refresh, clock);
        clock.set("2026-09-16T21:59:59Z");
        scheduler.poll();
        verifyNoInteractions(refresh);
        clock.set("2026-09-16T22:00:00Z");
        scheduler.poll();
        scheduler.poll();
        verify(refresh).registerTargets(LocalDate.of(2026, 9, 17));
        verify(refresh, times(2)).processBatch(LocalDate.of(2026, 9, 17));
        clock.set("2026-09-17T22:00:00Z");
        scheduler.poll();
        verify(refresh).registerTargets(LocalDate.of(2026, 9, 18));
    }

    @Test
    void retriesRegistrationOnTheNextPollAfterAnInterruptedScan() {
        RecommendationDailyRefreshService refresh = mock(RecommendationDailyRefreshService.class);
        RecommendationDailyRefreshScheduler scheduler = new RecommendationDailyRefreshScheduler(refresh, new TestClock());
        doThrow(new IllegalStateException("interrupted")).doNothing().when(refresh).registerTargets(any());
        assertThatThrownBy(scheduler::poll).isInstanceOf(IllegalStateException.class);
        verify(refresh, never()).processBatch(any());
        scheduler.poll();
        verify(refresh, times(2)).registerTargets(any());
        verify(refresh).processBatch(any());
    }

    @Test
    void honorsTheDisableFlagAndKeepsOtherScheduledWorkIndependentWhenEnabled() {
        RecommendationDailyRefreshService refresh = mock(RecommendationDailyRefreshService.class);
        ApplicationContextRunner runner = runner(refresh);
        runner.withPropertyValues("recommendation.daily-refresh.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(RecommendationDailyRefreshScheduler.class));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OtherWork other = new OtherWork(started);
        doAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(refresh).registerTargets(any());
        runner.withBean(OtherWork.class, () -> other)
                .withPropertyValues("recommendation.daily-refresh.enabled=true", "recommendation.daily-refresh.poll-interval=1h").run(context -> {
                    try {
                        assertThat(context).hasNotFailed();
                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                        other.progressed.get(5, TimeUnit.SECONDS);
                    } finally { release.countDown(); }
                });
    }

    private ApplicationContextRunner runner(RecommendationDailyRefreshService refresh) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(SchedulingConfig.class, RecommendationDailyRefreshScheduler.class)
                .withBean(RecommendationDailyRefreshService.class, () -> refresh).withBean(Clock.class, TestClock::new);
    }

    static class OtherWork {
        private final CountDownLatch started;
        final CompletableFuture<Void> progressed = new CompletableFuture<>();
        OtherWork(CountDownLatch started) { this.started = started; }
        @Scheduled(fixedDelay = 10)
        void poll() {
            if (started.getCount() == 0) { progressed.complete(null); }
        }
    }
}
