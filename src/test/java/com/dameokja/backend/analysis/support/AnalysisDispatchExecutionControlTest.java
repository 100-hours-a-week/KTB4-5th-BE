package com.dameokja.backend.analysis.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchChecker;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchLeaseService;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchSender;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchWorker;
import com.dameokja.backend.analysis.application.dispatch.LeasedDispatch;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalysisDispatchExecutionControlTest {
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);
    private final AnalysisDispatchLeaseService leases = mock(AnalysisDispatchLeaseService.class);
    private final AnalysisDispatchSender sender = mock(AnalysisDispatchSender.class);
    private final AiAnalysisClient ai = mock(AiAnalysisClient.class);
    private final AnalysisDispatchExecutionControl execution = new AnalysisDispatchExecutionControl();
    private AnalysisDispatchWorker worker;

    @BeforeEach
    void observeRealWorkerExecution() {
        worker = new AnalysisDispatchWorker(leases, sender, mock(AnalysisDispatchChecker.class), ai);
        execution.postProcessBeforeInitialization(worker, "worker");
    }

    @AfterEach
    void stopWorker() {
        execution.awaitIdle(WAIT_LIMIT);
        worker.shutdown();
    }

    @Test
    void waitsForBackgroundExecutionBeforeAllowingSharedStateCleanup() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> executingThread = new AtomicReference<>();
        when(ai.ready()).thenAnswer(invocation -> {
            executingThread.set(Thread.currentThread());
            entered.countDown();
            await(release);
            return false;
        });
        try {
            worker.wakeUp();
            await(entered);
            assertThat(executingThread.get()).isNotSameAs(Thread.currentThread());
            assertBusy();
        } finally {
            release.countDown();
        }
        execution.awaitIdle(WAIT_LIMIT);
    }

    @Test
    void includesChildDispatchesAndTheirFollowUpPollsInTheCompletionBoundary() {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch following = new CountDownLatch(1);
        CountDownLatch releaseFollowUp = new CountDownLatch(1);
        blockFollowUpPoll(following, releaseFollowUp);
        when(leases.leaseDue()).thenReturn(List.of(new LeasedDispatch(1L, "lease"))).thenReturn(List.of());
        doAnswer(invocation -> {
            sending.countDown();
            await(releaseSend);
            worker.wakeUp();
            return null;
        }).when(sender).send(any());
        try {
            worker.wakeUp();
            await(sending);
            worker.poll();
            assertBusy();
            releaseSend.countDown();
            await(following);
            assertBusy();
        } finally {
            releaseSend.countDown();
            releaseFollowUp.countDown();
        }
        execution.awaitIdle(WAIT_LIMIT);
        verify(sender).send(new LeasedDispatch(1L, "lease"));
    }

    @Test
    void doesNotLeaveOutstandingTasksWhenTheExecutorRejectsSubmission() {
        worker.shutdown();
        worker.wakeUp();
        execution.awaitIdle(Duration.ZERO);
        verify(ai, never()).ready();
    }

    private void blockFollowUpPoll(CountDownLatch following, CountDownLatch releaseFollowUp) {
        AtomicInteger polls = new AtomicInteger();
        when(ai.ready()).thenAnswer(invocation -> {
            if (polls.incrementAndGet() == 3) {
                following.countDown();
                await(releaseFollowUp);
            }
            return true;
        });
    }

    private void assertBusy() {
        assertThatThrownBy(() -> execution.awaitIdle(Duration.ZERO)).hasCauseInstanceOf(TimeoutException.class);
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
