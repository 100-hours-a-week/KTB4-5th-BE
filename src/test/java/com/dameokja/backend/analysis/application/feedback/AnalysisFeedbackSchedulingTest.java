package com.dameokja.backend.analysis.application.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class AnalysisFeedbackSchedulingTest {
    private final AnalysisFeedbackOutboxWorker worker = mock(AnalysisFeedbackOutboxWorker.class);
    private final AnalysisFeedbackSender http = mock(AnalysisFeedbackSender.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Scheduling.class, AnalysisKafkaConfig.class, AnalysisFeedbackOutboxScheduler.class, AnalysisFeedbackScheduler.class)
            .withBean(AnalysisFeedbackOutboxWorker.class, () -> worker).withBean(AnalysisFeedbackSender.class, () -> http)
            .withBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
            .withPropertyValues("analysis.kafka.request-topic=requested", "analysis.kafka.completion-topic=completed", "analysis.kafka.completion-dlt-topic=completed.dlt",
                    "analysis.kafka.completion-retry-delay=5s", "analysis.kafka.completion-group-id=test", "analysis.kafka.feedback-topic=feedback",
                    "analysis.outbox.batch-size=2", "analysis.outbox.lease-duration=1m", "analysis.outbox.retry-delay=5s", "analysis.feedback.poll-interval=1h");

    @Test
    void startsHttpAndKafkaPollingWithKafkaOnItsDedicatedScheduler() {
        CountDownLatch started = new CountDownLatch(2);
        AtomicReference<String> kafkaThread = new AtomicReference<>();
        doAnswer(invocation -> {
            kafkaThread.set(Thread.currentThread().getName());
            started.countDown();
            return null;
        }).when(worker).sendDue();
        doAnswer(invocation -> {
            started.countDown();
            return null;
        }).when(http).sendDue();
        context.withPropertyValues("analysis.feedback.enabled=true").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(kafkaThread.get()).startsWith("analysis-feedback-");
        });
    }

    @Test
    void disablesBothPollingPathsWithTheExistingFeedbackSwitch() {
        context.withPropertyValues("analysis.feedback.enabled=false").run(application -> {
            assertThat(application).hasNotFailed().doesNotHaveBean(AnalysisFeedbackOutboxScheduler.class).doesNotHaveBean(AnalysisFeedbackScheduler.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}
}
