package com.dameokja.backend.analysis.application;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class AnalysisJobSubmitServiceTest {
    private final Clock clock = mock(Clock.class);
    private final Instant instant = Instant.parse("2026-10-08T01:00:00Z");
    private final Duration duration = Duration.ofMinutes(1);
    private final ImageUploadMetadataStore imageUploadMetadataStore = new ImageUploadMetadataStore(clock, duration, redisTemplate(), JsonMapper.builder().build());
    private final AnalysisJobStore analysisJobStore = spy(new AnalysisJobStore(clock, duration, redisTemplate(), JsonMapper.builder().build()));
    private final AnalysisDispatchQueue dispatchQueue = mock(AnalysisDispatchQueue.class);
    private final AnalysisDispatchWorker dispatchWorker = mock(AnalysisDispatchWorker.class);
    private final AnalysisJobSubmitService analysisJobSubmitService = new AnalysisJobSubmitService(
            new ImageAnalysisInputService(imageUploadMetadataStore), analysisJobStore, dispatchQueue, dispatchWorker);

    @Test
    void queuesEveryImageInOrderAndReturnsTheQueuedGroupWithoutWaitingForAi() {
        issue("first", "ab");
        issue("second", "cd");
        AnalysisJob analysisJob = analysisJobSubmitService.submit(7L, List.of("first", "second"), "RECEIPT");
        ArgumentCaptor<List<AnalysisImage>> images = imagesCaptor();
        InOrder order = inOrder(dispatchQueue, analysisJobStore, dispatchWorker);
        order.verify(dispatchQueue).enqueue(eq(analysisJob.id()), eq(7L), eq("RECEIPT"), images.capture());
        order.verify(analysisJobStore).create(analysisJob.id(), 7L, "RECEIPT", images.getValue());
        order.verify(dispatchWorker).wakeUp();
        assertThat(images.getValue()).extracting(AnalysisImage::objectKey).containsExactly("first", "second");
        assertThat(images.getValue()).extracting(AnalysisImage::sha256).containsExactly("ab".repeat(32), "cd".repeat(32));
        assertThat(images.getValue()).extracting(AnalysisImage::requestId).doesNotHaveDuplicates();
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsOnlyNulls();
        assertThat(analysisJob.submittedAt()).isEqualTo(instant.atZone(BusinessTime.ZONE).toOffsetDateTime());
        assertThat(analysisJob.expiresAt()).isNull();
    }

    // AI는 같은 requestId·같은 요청 내용이면 기존 분석을 돌려주므로, 같은 사진·같은 힌트의 재요청은 같은 requestId를 쓴다.
    @Test
    void reusesTheRequestIdForTheSamePhotoAndHintAndDropsRepeatedKeys() {
        issue("first", "ab");
        AnalysisJob first = analysisJobSubmitService.submit(7L, List.of("first", "first"), "RECEIPT");
        AnalysisJob again = analysisJobSubmitService.submit(7L, List.of("first"), "RECEIPT");
        AnalysisJob otherHint = analysisJobSubmitService.submit(7L, List.of("first"), "PRODUCT");

        assertThat(first.analysisImages()).hasSize(1);
        assertThat(again.id()).isNotEqualTo(first.id());
        assertThat(again.analysisImages().getFirst().requestId()).isEqualTo(first.analysisImages().getFirst().requestId());
        assertThat(otherHint.analysisImages().getFirst().requestId()).isNotEqualTo(first.analysisImages().getFirst().requestId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "other-owner", "profile", "expired"})
    void validatesEveryImageBeforeQueueingAny(String objectKey) {
        issue("first", "ab");
        if (!objectKey.equals("missing")) {
            imageUploadMetadataStore.save(objectKey.equals("other-owner") ? 8L : 7L,
                    objectKey.equals("profile") ? ImageUploadPurpose.PROFILE : ImageUploadPurpose.ANALYSIS, objectKey, "cd".repeat(32));
        }
        if (objectKey.equals("expired")) {
            when(clock.instant()).thenReturn(instant.plus(duration));
        }
        assertThatThrownBy(() -> analysisJobSubmitService.submit(7L, List.of("first", objectKey), null)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("IMAGE-422-001");
        verifyNoInteractions(dispatchQueue, dispatchWorker);
    }

    @Test
    void createsNoJobWhenTheQueueCannotBeSaved() {
        issue("first", "ab");
        doThrow(new DataAccessResourceFailureException("mysql")).when(dispatchQueue).enqueue(anyString(), any(), anyString(), anyList());
        assertThatThrownBy(() -> analysisJobSubmitService.submit(7L, List.of("first"), null)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(redisTemplate().keys("analysis:job:*")).isEmpty();
        verifyNoInteractions(dispatchWorker);
    }

    @Test
    void leavesQueuedImagesForTheWorkerWhenTheJobCannotBeCreated() {
        issue("first", "ab");
        doThrow(new DataAccessResourceFailureException("redis")).when(analysisJobStore).create(anyString(), any(), anyString(), anyList());
        assertThatThrownBy(() -> analysisJobSubmitService.submit(7L, List.of("first"), null)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(dispatchQueue).enqueue(anyString(), eq(7L), eq("AUTO"), anyList());
        verifyNoInteractions(dispatchWorker);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<AnalysisImage>> imagesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private void issue(String objectKey, String sha256Prefix) {
        when(clock.instant()).thenReturn(instant);
        when(clock.withZone(any())).thenReturn(Clock.fixed(instant, BusinessTime.ZONE));
        imageUploadMetadataStore.save(7L, ImageUploadPurpose.ANALYSIS, objectKey, sha256Prefix.repeat(32));
    }
}
