package com.dameokja.backend.analysis.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchLeaseService;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchProperties;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchSender;
import com.dameokja.backend.analysis.application.dispatch.LeasedDispatch;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchWorker;
import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackProperties;
import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackSender;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContextManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class RestartableAnalysisContractTestSupport extends AnalysisContractTestSupport {
    @Autowired private ConfigurableApplicationContext application;
    @Autowired private AnalysisDispatchProperties dispatchPolicy;
    @Autowired private AnalysisDispatchLeaseService dispatchLeases;
    @Autowired private AnalysisDispatchSender dispatchSender;
    @Autowired private AnalysisDispatchWorker dispatchWorker;
    @Autowired private AnalysisFeedbackProperties feedbackPolicy;
    @Autowired private AnalysisFeedbackSender feedbackSender;

    protected void restartApplication() throws Exception {
        Instant resumedAt = clock.instant();
        ConfigurableApplicationContext stopped = application;
        HikariDataSource stoppedPool = stopped.getBean(HikariDataSource.class);
        AiAnalysisClient stoppedAi = ai;
        TestContextManager contexts = new TestContextManager(getClass());
        // 캐시에서 제거하면서 실제 종료하고, DB·Redis는 정리하지 않은 채 같은 공통 설정으로 다시 띄운다.
        contexts.getTestContext().markApplicationContextDirty(DirtiesContext.HierarchyMode.CURRENT_LEVEL);
        assertThat(stopped.isActive()).isFalse();
        assertThat(stoppedPool.isClosed()).isTrue();
        contexts.prepareTestInstance(this);
        clock.set(resumedAt.toString());
        acceptAiRequests();
        assertThat(application).isNotSameAs(stopped);
        assertThat(application.isActive()).isTrue();
        assertThat(ai).isNotSameAs(stoppedAi);
    }

    @Override
    protected void dispatchDueAnalyses() {
        // 자동 만료 조회가 잠깐 잠근 작업은 같은 시각으로 다시 선점하고, 전송 자체는 호출 스레드에서 한 번만 실행한다.
        List<LeasedDispatch> dispatches = await().atMost(WAIT_LIMIT).until(dispatchLeases::leaseDue, leased -> !leased.isEmpty());
        dispatches.forEach(dispatchSender::send);
    }

    // 중단된 전송과 콜백 대기 모두 복구 대상이 되도록 시계만 진행하고 실제 워커를 실행한다.
    protected void recoverInterruptedAnalyses() {
        clock.set(clock.instant().plus(dispatchPolicy.leaseDuration()).plus(dispatchPolicy.callbackTimeout()).toString());
        dispatchWorker.poll();
    }

    protected void pollAnalyses() { dispatchWorker.poll(); }

    protected void sendFeedback() { feedbackSender.sendDue(); }

    protected void recoverInterruptedFeedback() {
        clock.set(clock.instant().plus(feedbackPolicy.leaseDuration()).toString());
        feedbackSender.sendDue();
    }
}
