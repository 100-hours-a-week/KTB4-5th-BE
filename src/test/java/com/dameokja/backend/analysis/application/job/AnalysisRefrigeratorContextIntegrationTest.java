package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchLeaseService;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchSender;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchWorker;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisRefrigeratorContextIntegrationTest extends ServiceIntegrationTest {
    @Autowired AnalysisJobSubmitService submissions;
    @Autowired AnalysisJobStore jobs;
    @Autowired ImageUploadMetadataStore uploads;
    @Autowired RefrigeratorAccessService access;
    @Autowired AnalysisDispatchLeaseService leases;
    @Autowired AnalysisDispatchSender sender;
    @MockitoBean AnalysisDispatchWorker worker;
    @MockitoBean AiAnalysisClient ai;

    @BeforeEach
    @AfterEach
    void cleanDispatches() { jdbcTemplate.update("DELETE FROM analysis_dispatches"); }

    @Test
    void retriesWithTheStoredSelectionAfterTheMemberSelectsAnotherRefrigerator() {
        Fixture owner = ownerFixture("접수자");
        uploads.save(owner.userId(), ImageUploadPurpose.ANALYSIS, "image", "ab".repeat(32));
        AnalysisJob job = submissions.submit(owner.userId(), List.of("image"), "AUTO");
        assertThat(job.refrigeratorId()).isEqualTo(owner.refrigeratorId()).isPositive();
        selectAnotherRefrigerator(owner);
        assertThat(access.findCurrentForRead(owner.userId()).getId()).isNotEqualTo(owner.refrigeratorId());
        when(ai.submit(any())).thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(new AiImageAnalysisSubmitResponse("ai-context", "QUEUED", null, 1000));

        sender.send(leases.leaseDue().getFirst());
        clock.set(clock.instant().plusSeconds(60).toString());
        sender.send(leases.leaseDue().getFirst());

        ArgumentCaptor<AiImageAnalysisSubmitRequest> requests = ArgumentCaptor.forClass(AiImageAnalysisSubmitRequest.class);
        verify(ai, times(2)).submit(requests.capture());
        assertThat(requests.getAllValues()).extracting(AiImageAnalysisSubmitRequest::refrigeratorId).containsOnly(owner.refrigeratorId());
        assertThat(requests.getAllValues()).extracting(AiImageAnalysisSubmitRequest::requestId).containsOnly(job.analysisImages().getFirst().requestId());
        assertThat(jobs.findOwned(job.id(), owner.userId()).refrigeratorId()).isEqualTo(owner.refrigeratorId());
    }

    private void selectAnotherRefrigerator(Fixture owner) {
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active=false WHERE user_id=?", owner.userId());
        Refrigerator other = refrigerator("다른 냉장고");
        join(userRepository.findById(owner.userId()).orElseThrow(), other, true, true);
    }
}
