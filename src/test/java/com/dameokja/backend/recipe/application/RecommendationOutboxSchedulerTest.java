package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.dameokja.backend.global.config.SchedulingConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.scheduling.annotation.Scheduled;

class RecommendationOutboxSchedulerTest {
    @Test
    void keepsDefaultSchedulerRunningWhileKafkaPublicationWaits() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OtherScheduledWork other = new OtherScheduledWork(started);
        RecommendationOutboxWorker worker = mock(RecommendationOutboxWorker.class);
        doAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(worker).publishBatch();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(SchedulingConfig.class, RecommendationOutboxScheduler.class)
                .withBean(RecommendationOutboxWorker.class, () -> worker)
                .withBean(OtherScheduledWork.class, () -> other)
                .withPropertyValues("recommendation.outbox.enabled=true", "recommendation.outbox.poll-interval=1h").run(context -> {
                    try {
                        assertThat(context).hasNotFailed();
                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                        other.progressed.get(5, TimeUnit.SECONDS);
                    } finally {
                        release.countDown();
                    }
                });
    }

    static class OtherScheduledWork {
        private final CountDownLatch kafkaStarted;
        final CompletableFuture<Void> progressed = new CompletableFuture<>();

        OtherScheduledWork(CountDownLatch kafkaStarted) {
            this.kafkaStarted = kafkaStarted;
        }

        @Scheduled(fixedDelay = 10)
        void poll() {
            if (kafkaStarted.getCount() == 0) { progressed.complete(null); }
        }
    }
}
