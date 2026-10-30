package com.dameokja.backend.analysis.application.job;

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

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchQueue;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchWorker;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
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
    private final AnalysisDispatchQueue analysisDispatchQueue = mock(AnalysisDispatchQueue.class);
    private final AnalysisDispatchWorker analysisDispatchWorker = mock(AnalysisDispatchWorker.class);
    private final RefrigeratorAccessService access = mock(RefrigeratorAccessService.class);
    private final AnalysisJobSubmitService analysisJobSubmitService = new AnalysisJobSubmitService(
            new ImageAnalysisInputService(imageUploadMetadataStore), analysisJobStore, analysisDispatchQueue, analysisDispatchWorker, access);

    @BeforeEach
    void selectRefrigerator() { when(access.findCurrentForRead(7L)).thenReturn(refrigerator(17L)); }

    @Test
    void queuesEveryImageInOrderAndReturnsTheQueuedGroupWithoutWaitingForAi() {
        issue("first", "ab");
        issue("second", "cd");
        AnalysisJob analysisJob = analysisJobSubmitService.submit(7L, List.of("first", "second"), "RECEIPT");
        ArgumentCaptor<List<AnalysisImage>> analysisImages = imagesCaptor();
        InOrder order = inOrder(analysisDispatchQueue, analysisJobStore, analysisDispatchWorker);
        order.verify(analysisDispatchQueue).enqueue(eq(analysisJob.id()), eq(7L), eq("RECEIPT"), analysisImages.capture());
        order.verify(analysisJobStore).create(analysisJob.id(), 7L, 17L, "RECEIPT", analysisImages.getValue());
        order.verify(analysisDispatchWorker).wakeUp();
        assertThat(analysisImages.getValue()).extracting(AnalysisImage::objectKey).containsExactly("first", "second");
        assertThat(analysisImages.getValue()).extracting(AnalysisImage::sha256).containsExactly("ab".repeat(32), "cd".repeat(32));
        assertThat(analysisImages.getValue()).extracting(AnalysisImage::requestId).doesNotHaveDuplicates();
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsOnlyNulls();
        assertThat(analysisJob.submittedAt()).isEqualTo(instant.atZone(BusinessTime.ZONE).toOffsetDateTime());
        assertThat(analysisJob.expiresAt()).isNull();
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(mapper.writeValueAsString(analysisJob)).path("refrigeratorId").asLong()).isEqualTo(17L);
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

    @Test
    void separatesThePreviousContractAndRefrigeratorContextInRequestIds() {
        issue("first", "ab");
        AnalysisJob first = analysisJobSubmitService.submit(7L, List.of("first"), "RECEIPT");
        when(access.findCurrentForRead(7L)).thenReturn(refrigerator(18L));
        AnalysisJob otherFridge = analysisJobSubmitService.submit(7L, List.of("first"), "RECEIPT");
        imageUploadMetadataStore.save(8L, ImageUploadPurpose.ANALYSIS, "first", "ab".repeat(32));
        when(access.findCurrentForRead(8L)).thenReturn(refrigerator(17L));
        AnalysisJob otherUser = analysisJobSubmitService.submit(8L, List.of("first"), "RECEIPT");
        String previous = UUID.nameUUIDFromBytes(String.join("\n", "7", "first", "ab".repeat(32), "RECEIPT")
                .getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(List.of(first, otherFridge, otherUser)).extracting(job -> job.analysisImages().getFirst().requestId()).doesNotHaveDuplicates();
        assertThat(first.analysisImages().getFirst().requestId()).isNotEqualTo(previous);
        assertThat(analysisJobStore.findOwned(first.id(), 7L).refrigeratorId()).isEqualTo(17L);
    }

    @Test
    void refusesAnUnavailableSelectionBeforeWritingTheQueueOrJob() {
        issue("first", "ab");
        when(access.findCurrentForRead(7L)).thenThrow(new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED));
        assertThatThrownBy(() -> analysisJobSubmitService.submit(7L, List.of("first"), null))
                .isInstanceOfSatisfying(CustomException.class, exception ->
                        assertThat(exception.getExceptionCode()).isEqualTo(RefrigeratorExceptionCode.ACCESS_DENIED));
        assertThat(redisTemplate().keys("analysis:job:*")).isEmpty();
        verifyNoInteractions(analysisDispatchQueue, analysisDispatchWorker);
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
        verifyNoInteractions(analysisDispatchQueue, analysisDispatchWorker);
    }

    @Test
    void createsNoJobWhenTheQueueCannotBeSaved() {
        issue("first", "ab");
        doThrow(new DataAccessResourceFailureException("mysql")).when(analysisDispatchQueue).enqueue(anyString(), any(), anyString(), anyList());
        assertThatThrownBy(() -> analysisJobSubmitService.submit(7L, List.of("first"), null)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(redisTemplate().keys("analysis:job:*")).isEmpty();
        verifyNoInteractions(analysisDispatchWorker);
    }

    @Test
    void leavesQueuedImagesForTheWorkerWhenTheJobCannotBeCreated() {
        issue("first", "ab");
        doThrow(new DataAccessResourceFailureException("redis")).when(analysisJobStore).create(anyString(), any(), any(), anyString(), anyList());
        assertThatThrownBy(() -> analysisJobSubmitService.submit(7L, List.of("first"), null)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(analysisDispatchQueue).enqueue(anyString(), eq(7L), eq("AUTO"), anyList());
        verifyNoInteractions(analysisDispatchWorker);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<AnalysisImage>> imagesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private Refrigerator refrigerator(Long id) {
        Refrigerator refrigerator = new Refrigerator("선택", "2026-10");
        ReflectionTestUtils.setField(refrigerator, "id", id);
        return refrigerator;
    }

    private void issue(String objectKey, String sha256Prefix) {
        when(clock.instant()).thenReturn(instant);
        when(clock.withZone(any())).thenReturn(Clock.fixed(instant, BusinessTime.ZONE));
        imageUploadMetadataStore.save(7L, ImageUploadPurpose.ANALYSIS, objectKey, sha256Prefix.repeat(32));
    }
}
