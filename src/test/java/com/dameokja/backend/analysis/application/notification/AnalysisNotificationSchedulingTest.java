package com.dameokja.backend.analysis.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class AnalysisNotificationSchedulingTest {
    private final AnalysisDatabaseCompletionNotifier analysisDatabaseCompletionNotifier = mock(AnalysisDatabaseCompletionNotifier.class);
    private final AnalysisCompletionNotifier analysisCompletionNotifier = mock(AnalysisCompletionNotifier.class);
    private final ApplicationContextRunner schedulingContext = new ApplicationContextRunner()
            .withUserConfiguration(Scheduling.class, AnalysisNotificationConfig.class, AnalysisDatabaseNotificationScheduler.class, AnalysisPendingNotificationScheduler.class)
            .withBean(AnalysisDatabaseCompletionNotifier.class, () -> analysisDatabaseCompletionNotifier)
            .withBean(AnalysisCompletionNotifier.class, () -> analysisCompletionNotifier)
            .withBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
            .withPropertyValues("analysis.notification.poll-interval=1h");

    @Test
    void startsBothPollingPathsWithDatabaseOnItsDedicatedScheduler() {
        CountDownLatch pollingStarted = new CountDownLatch(2);
        AtomicReference<String> databasePollingThreadName = new AtomicReference<>();
        doAnswer(invocation -> {
            databasePollingThreadName.set(Thread.currentThread().getName());
            pollingStarted.countDown();
            return null;
        }).when(analysisDatabaseCompletionNotifier).deliverPending();
        doAnswer(invocation -> {
            pollingStarted.countDown();
            return null;
        }).when(analysisCompletionNotifier).deliverPending();
        schedulingContext.withPropertyValues("analysis.notification.retry-enabled=true").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(pollingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(databasePollingThreadName.get()).startsWith("analysis-notification-");
        });
    }

    @Test
    void disablesBothPollingPathsWithTheExistingSwitch() {
        schedulingContext.withPropertyValues("analysis.notification.retry-enabled=false").run(application -> {
            assertThat(application).hasNotFailed().doesNotHaveBean(AnalysisDatabaseNotificationScheduler.class)
                    .doesNotHaveBean(AnalysisPendingNotificationScheduler.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}
}
