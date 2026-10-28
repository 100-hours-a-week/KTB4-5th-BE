package com.dameokja.backend.analysis.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContextManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class RestartableAnalysisContractTestSupport extends AnalysisContractTestSupport {
    @Autowired private ConfigurableApplicationContext application;

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
}
