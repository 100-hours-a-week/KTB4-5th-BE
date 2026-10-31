package com.dameokja.backend.analysis.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisExpirationDateType;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedEvent;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContextManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class RestartableAnalysisContractTestSupport extends AnalysisDatabaseContractTestSupport {
    @Autowired private ConfigurableApplicationContext application;
    @Autowired private AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired private AnalysisCompletionPersistence analysisCompletionPersistence;

    protected void restartApplication() throws Exception {
        Instant resumedAt = clock.instant();
        ConfigurableApplicationContext stoppedApplication = application;
        HikariDataSource stoppedPool = stoppedApplication.getBean(HikariDataSource.class);
        AnalysisKafkaPublisher stoppedPublisher = analysisKafkaPublisher;
        TestContextManager testContexts = new TestContextManager(getClass());
        // 실제 컨텍스트와 커넥션 풀을 종료하되 DB·Redis 데이터는 유지한다.
        testContexts.getTestContext().markApplicationContextDirty(DirtiesContext.HierarchyMode.CURRENT_LEVEL);
        assertThat(stoppedApplication.isActive()).isFalse();
        assertThat(stoppedPool.isClosed()).isTrue();
        testContexts.prepareTestInstance(this);
        clock.set(resumedAt.toString());
        assertThat(application).isNotSameAs(stoppedApplication);
        assertThat(application.isActive()).isTrue();
        assertThat(analysisKafkaPublisher).isNotSameAs(stoppedPublisher);
    }

    protected String acceptAnalysis(Long userId) throws Exception {
        String response = submitImage(userId, "PRODUCT").andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).at("/data/analysisId").asString();
    }

    protected void completeStoredAnalysis(String analysisJobId, Long userId) {
        AnalysisJob analysisJob = analysisJobStateAccess.findOwned(analysisJobId, userId);
        AnalysisCompletionItem analysisCompletionItem = new AnalysisCompletionItem("milk", RecognitionStatus.RECOGNIZED, "우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 1, new AnalysisCompletionItem.Weight(null, WeightUnit.NONE),
                new AnalysisCompletionItem.Expiration(null, AnalysisExpirationDateType.UNKNOWN, null), List.of());
        analysisCompletionPersistence.apply(new AnalysisCompletedEvent(UUID.randomUUID().toString(), new AnalysisCompletedEvent.Payload("AnalysisCompleted",
                clock.instant(), analysisJob.analysisImages().getFirst().requestId(), userId, AI_ANALYSIS_ID, AnalysisStatus.COMPLETED, clock.instant(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of(analysisCompletionItem)), null)));
    }
}
