package com.dameokja.backend.analysis.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchQueue;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchWorker;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobRequestRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxRepository;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaProperties;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class AnalysisRequestIdentityTest {
    private static final String IMAGE_SHA256 = "ab".repeat(32);
    private final RefrigeratorAccessService refrigeratorAccessService = mock(RefrigeratorAccessService.class);
    private final ImageAnalysisInputService imageAnalysisInputService = mock(ImageAnalysisInputService.class);
    private final AnalysisDispatchQueue analysisDispatchQueue = mock(AnalysisDispatchQueue.class);
    private final UserAccessService userAccessService = mock(UserAccessService.class);
    private final AnalysisJobStateRepository analysisJobStateRepository = mock(AnalysisJobStateRepository.class);
    private final AnalysisJobAcceptance analysisJobAcceptance = new AnalysisJobAcceptance(refrigeratorAccessService, userAccessService, analysisJobStateRepository,
            mock(AnalysisJobRequestRepository.class), mock(AnalysisOutboxRepository.class),
            new AnalysisKafkaProperties("request", "completion", "dlt", java.time.Duration.ofSeconds(1), "group", "feedback"),
            JsonMapper.builder().build(), Clock.fixed(Instant.parse("2026-10-29T03:00:00Z"), ZoneOffset.UTC));
    private final AnalysisJobSubmitService analysisJobSubmitService = new AnalysisJobSubmitService(imageAnalysisInputService, mock(AnalysisJobStore.class), analysisDispatchQueue,
            mock(AnalysisDispatchWorker.class), refrigeratorAccessService, analysisJobAcceptance, false);

    @Test
    void bothPathsUseTheExistingHttpIdentityAndNormalizeNullHint() {
        selectRefrigerator(7L, 17L);
        when(imageAnalysisInputService.findAnalysisSha256(7L, "first")).thenReturn(IMAGE_SHA256);
        when(analysisJobStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        analysisJobSubmitService.submit(7L, List.of("first"), null);
        ArgumentCaptor<List<AnalysisImage>> analysisImagesCaptor = analysisImagesCaptor();
        verify(analysisDispatchQueue).enqueue(anyString(), eq(7L), eq("AUTO"), analysisImagesCaptor.capture());
        String httpRequestId = analysisImagesCaptor.getValue().getFirst().requestId();
        AnalysisJob kafkaAnalysisJob = analysisJobAcceptance.accept(7L, null, List.of(AnalysisImage.pending("first", IMAGE_SHA256)));
        AnalysisJob explicitAutoAnalysisJob = analysisJobAcceptance.accept(7L, "AUTO", List.of(AnalysisImage.pending("first", IMAGE_SHA256)));
        assertThat(httpRequestId).isEqualTo("d6b9b959-3242-3cf5-9f8d-f5e24d562286");
        assertThat(kafkaAnalysisJob.analysisImages().getFirst().requestId()).isEqualTo(httpRequestId);
        assertThat(explicitAutoAnalysisJob.analysisImages().getFirst().requestId()).isEqualTo(httpRequestId);
    }

    @Test
    void eachContextFieldChangesTheRequestIdentity() {
        when(analysisJobStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        selectRefrigerator(7L, 17L);
        selectRefrigerator(8L, 17L);
        String baseRequestId = acceptAndGetRequestId(7L, "first", IMAGE_SHA256, "AUTO");
        assertThat(acceptAndGetRequestId(8L, "first", IMAGE_SHA256, "AUTO")).isNotEqualTo(baseRequestId);
        assertThat(acceptAndGetRequestId(7L, "second", IMAGE_SHA256, "AUTO")).isNotEqualTo(baseRequestId);
        assertThat(acceptAndGetRequestId(7L, "first", "cd".repeat(32), "AUTO")).isNotEqualTo(baseRequestId);
        assertThat(acceptAndGetRequestId(7L, "first", IMAGE_SHA256, "PRODUCT")).isNotEqualTo(baseRequestId);
        selectRefrigerator(7L, 18L);
        assertThat(acceptAndGetRequestId(7L, "first", IMAGE_SHA256, "AUTO")).isNotEqualTo(baseRequestId);
    }

    private String acceptAndGetRequestId(Long userId, String imageObjectKey, String imageSha256, String inputHint) {
        return analysisJobAcceptance.accept(userId, inputHint, List.of(AnalysisImage.pending(imageObjectKey, imageSha256))).analysisImages().getFirst().requestId();
    }

    private void selectRefrigerator(Long userId, Long refrigeratorId) {
        Refrigerator refrigerator = new Refrigerator("선택", "2026-10");
        ReflectionTestUtils.setField(refrigerator, "id", refrigeratorId);
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(userAccessService.getActive(userId)).thenReturn(user);
        when(refrigeratorAccessService.findCurrentForRead(userId)).thenReturn(refrigerator);
        when(refrigeratorAccessService.lockCurrentForWrite(userId)).thenReturn(refrigerator);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<AnalysisImage>> analysisImagesCaptor() { return ArgumentCaptor.forClass(List.class); }
}
