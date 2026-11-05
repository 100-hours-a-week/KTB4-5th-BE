package com.dameokja.backend.analysis.support;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchWorker;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

@TestConfiguration(proxyBeanMethods = false)
public class AnalysisDispatchExecutionControl implements BeanPostProcessor {
    private final Phaser tasks = new Phaser() {
        @Override
        protected boolean onAdvance(int phase, int registeredParties) { return false; }
    };

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof AnalysisDispatchWorker worker) {
            // 운영 실행기를 그대로 사용하면서 제출 시점부터 후속 작업의 완료까지 추적한다.
            ExecutorService executor = (ExecutorService) ReflectionTestUtils.getField(worker, "executor");
            ExecutorService observedExecutor = mock(ExecutorService.class, delegatesTo(executor));
            doAnswer(invocation -> {
                execute(executor, invocation.getArgument(0));
                return null;
            }).when(observedExecutor).execute(any(Runnable.class));
            ReflectionTestUtils.setField(worker, "executor", observedExecutor);
        }
        return bean;
    }

    public void awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (tasks.getRegisteredParties() != 0) {
                int phase = tasks.getPhase();
                if (tasks.getRegisteredParties() != 0) {
                    tasks.awaitAdvanceInterruptibly(phase, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("분석 전달 작업의 완료 대기가 중단되었습니다.", exception);
        } catch (TimeoutException exception) {
            throw new AssertionError("분석 전달 작업이 남아 있어 공유 상태를 초기화할 수 없습니다. remaining=" + tasks.getRegisteredParties(), exception);
        }
    }

    private void execute(ExecutorService executor, Runnable task) {
        tasks.register();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    tasks.arriveAndDeregister();
                }
            });
        } catch (RuntimeException | Error exception) {
            tasks.arriveAndDeregister();
            throw exception;
        }
    }
}
