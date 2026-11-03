package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.support.AnalysisDatabaseContractTestSupport;
import com.dameokja.backend.analysis.application.outbox.AnalysisOutboxWorker;
import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.ObjectMapper;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisRefrigeratorContextIntegrationTest extends AnalysisDatabaseContractTestSupport {
    @Autowired AnalysisJobSubmitService submissions;
    @Autowired AnalysisJobStateAccess jobs;
    @Autowired ImageUploadMetadataStore uploads;
    @Autowired RefrigeratorAccessService access;
    @Autowired AnalysisOutboxWorker worker;
    @Autowired ObjectMapper mapper;
    @MockitoBean AnalysisKafkaPublisher publisher;

    @Test
    void retriesWithTheStoredSelectionAfterTheMemberSelectsAnotherRefrigerator() {
        Fixture owner = ownerFixture("접수자");
        uploads.save(owner.userId(), ImageUploadPurpose.ANALYSIS, "image", "ab".repeat(32));
        AnalysisJob job = submissions.submit(owner.userId(), List.of("image"), "AUTO");
        assertThat(job.refrigeratorId()).isEqualTo(owner.refrigeratorId()).isPositive();
        selectAnotherRefrigerator(owner);
        assertThat(access.findCurrentForRead(owner.userId()).getId()).isNotEqualTo(owner.refrigeratorId());
        when(publisher.publish(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));

        worker.publishBatch();
        clock.set(clock.instant().plusSeconds(60).toString());
        worker.publishBatch();

        ArgumentCaptor<AnalysisOutbox> events = ArgumentCaptor.forClass(AnalysisOutbox.class);
        verify(publisher, times(2)).publish(events.capture());
        assertThat(events.getAllValues()).extracting(AnalysisOutbox::getMessageBody).containsOnly(events.getValue().getMessageBody());
        assertThat(mapper.readTree(events.getValue().getMessageBody()).at("/payload/refrigeratorId").asLong()).isEqualTo(owner.refrigeratorId());
        assertThat(mapper.readTree(events.getValue().getMessageBody()).at("/payload/requestId").asText()).isEqualTo(job.analysisImages().getFirst().requestId());
        assertThat(jobs.findOwned(job.id(), owner.userId()).refrigeratorId()).isEqualTo(owner.refrigeratorId());
    }

    private void selectAnotherRefrigerator(Fixture owner) {
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active=false WHERE user_id=?", owner.userId());
        Refrigerator other = refrigerator("다른 냉장고");
        join(userRepository.findById(owner.userId()).orElseThrow(), other, true, true);
    }
}
