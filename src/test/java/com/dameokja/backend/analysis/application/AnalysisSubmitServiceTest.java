package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisAccepted;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisRequest;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

class AnalysisSubmitServiceTest {
    private final Clock clock = mock(Clock.class);
    private final Instant instant = Instant.parse("2026-10-08T01:00:00Z");
    private final Duration duration = Duration.ofMinutes(1);
    private final ImageUploadMetadataStore imageUploadMetadataStore = new ImageUploadMetadataStore(clock, duration);
    private final AnalysisJobStore analysisJobStore = new AnalysisJobStore(clock, duration);
    private final AiAnalysisClient aiAnalysisClient = mock(AiAnalysisClient.class);
    private final AnalysisSubmitService analysisSubmitService = new AnalysisSubmitService(new ImageAnalysisInputService(imageUploadMetadataStore), analysisJobStore, aiAnalysisClient);

    @Test
    void submitsStoredChecksumsInOrderAndReturnsTheGroupAfterAllRegistrations() {
        issue("first", "ab");
        issue("second", "cd");
        when(aiAnalysisClient.submit(any())).thenReturn(accepted("ai-first"), accepted("ai-second"));
        AnalysisJob analysisJob = analysisSubmitService.submit(7L, List.of("first", "second"), "RECEIPT");
        ArgumentCaptor<AiAnalysisRequest> argumentCaptor = ArgumentCaptor.forClass(AiAnalysisRequest.class);
        verify(aiAnalysisClient, times(2)).submit(argumentCaptor.capture());
        List<AiAnalysisRequest> aiAnalysisRequests = argumentCaptor.getAllValues();
        assertThat(aiAnalysisRequests).extracting(aiAnalysisRequest -> aiAnalysisRequest.image().objectKey()).containsExactly("first", "second");
        assertThat(aiAnalysisRequests).extracting(aiAnalysisRequest -> aiAnalysisRequest.image().sha256()).containsExactly("ab".repeat(32), "cd".repeat(32));
        assertThat(aiAnalysisRequests).extracting(AiAnalysisRequest::inputHint).containsOnly("RECEIPT");
        assertThat(analysisJob.analysisImages()).extracting(AnalysisImage::requestId).doesNotHaveDuplicates()
                .containsExactlyElementsOf(aiAnalysisRequests.stream().map(AiAnalysisRequest::requestId).toList());
        assertThat(analysisJob.id()).isNotBlank().isNotIn("ai-first", "ai-second");
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsExactly("ai-first", "ai-second");
        assertThat(analysisJob.submittedAt()).isEqualTo(instant.atZone(BusinessTime.ZONE).toOffsetDateTime());
        assertThat(analysisJob.expiresAt()).isEqualTo(analysisJob.submittedAt().plus(duration));
        verifyNoMoreInteractions(aiAnalysisClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "other-owner", "profile", "expired"})
    void validatesEveryImageBeforeSubmittingAnyToAi(String objectKey) {
        issue("first", "ab");
        if (!objectKey.equals("missing")) {
            imageUploadMetadataStore.save(objectKey.equals("other-owner") ? 8L : 7L,
                    objectKey.equals("profile") ? ImageUploadPurpose.PROFILE : ImageUploadPurpose.ANALYSIS, objectKey, "cd".repeat(32));
        }
        if (objectKey.equals("expired")) {
            when(clock.instant()).thenReturn(instant.plus(duration));
        }
        assertThatThrownBy(() -> analysisSubmitService.submit(7L, List.of("first", objectKey), null)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("IMAGE-422-001");
        verifyNoInteractions(aiAnalysisClient);
    }

    @ParameterizedTest
    @CsvSource({"0,IMAGE-503-001", "400,GLOBAL-400-001", "429,IMAGE-429-001", "503,IMAGE-503-001"})
    void stopsOnSecondAcceptanceFailureInsteadOfReturningAnIncompleteGroup(int status, String code) {
        issue("first", "ab");
        issue("second", "cd");
        issue("third", "ef");
        RestClientException restClientException = status == 0 ? new RestClientException("timeout")
                : new RestClientResponseException("AI rejection", status, "error", null, null, null);
        when(aiAnalysisClient.submit(any())).thenReturn(accepted("ai-first")).thenThrow(restClientException);
        assertThatThrownBy(() -> analysisSubmitService.submit(7L, List.of("first", "second", "third"), null)).isInstanceOf(CustomException.class)
                .hasCause(restClientException).extracting("exceptionCode.code").isEqualTo(code);
        verify(aiAnalysisClient, times(2)).submit(argThat(aiAnalysisRequest -> "AUTO".equals(aiAnalysisRequest.inputHint())));
        verifyNoMoreInteractions(aiAnalysisClient);
    }

    @Test
    void refusesAcceptanceWithoutAnAiJobId() {
        issue("first", "ab");
        when(aiAnalysisClient.submit(any())).thenReturn(accepted(" "));
        assertThatThrownBy(() -> analysisSubmitService.submit(7L, List.of("first"), null)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("IMAGE-503-001");
    }

    private void issue(String objectKey, String sha256Prefix) {
        when(clock.instant()).thenReturn(instant);
        when(clock.withZone(any())).thenReturn(Clock.fixed(instant, BusinessTime.ZONE));
        imageUploadMetadataStore.save(7L, ImageUploadPurpose.ANALYSIS, objectKey, sha256Prefix.repeat(32));
    }

    private AiAnalysisAccepted accepted(String id) { return new AiAnalysisAccepted(id, "QUEUED", instant.atZone(BusinessTime.ZONE).toOffsetDateTime(), 1000); }
}
