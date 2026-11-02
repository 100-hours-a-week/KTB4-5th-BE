package com.dameokja.backend.analysis.application.job;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class AnalysisJobSubmitServiceTest {
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-08T01:00:00Z");
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(1);
    private final Clock clock = mock(Clock.class);
    private final ImageUploadMetadataStore imageUploadMetadataStore = new ImageUploadMetadataStore(clock, UPLOAD_TTL, redisTemplate(), JsonMapper.builder().build());
    private final AnalysisJobAcceptance analysisJobAcceptance = mock(AnalysisJobAcceptance.class);
    private final RefrigeratorAccessService refrigeratorAccessService = mock(RefrigeratorAccessService.class);
    private final Refrigerator refrigerator = mock(Refrigerator.class);
    private final AnalysisJobSubmitService analysisJobSubmitService = new AnalysisJobSubmitService(
            new ImageAnalysisInputService(imageUploadMetadataStore), analysisJobAcceptance, refrigeratorAccessService);

    @BeforeEach
    void acceptValidImages() {
        when(clock.instant()).thenReturn(SUBMITTED_AT);
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), BusinessTime.ZONE));
        when(refrigeratorAccessService.findCurrentForRead(7L)).thenReturn(refrigerator);
        when(refrigerator.getId()).thenReturn(11L);
        when(analysisJobAcceptance.accept(any(), any(), anyString(), anyList())).thenAnswer(invocation -> new AnalysisJob(UUID.randomUUID().toString(),
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3),
                SUBMITTED_AT.atZone(BusinessTime.ZONE).toOffsetDateTime(), null, false));
    }

    @Test
    void passesValidatedImagesInOrderToAtomicDatabaseAcceptance() {
        issue("first", "ab");
        issue("second", "cd");
        AnalysisJob analysisJob = analysisJobSubmitService.submit(7L, List.of("first", "second"), "RECEIPT");
        verify(analysisJobAcceptance).accept(7L, 11L, "RECEIPT", analysisJob.analysisImages());
        assertThat(analysisJob.refrigeratorId()).isEqualTo(11L);
        assertThat(analysisJob.analysisImages()).extracting(AnalysisImage::objectKey).containsExactly("first", "second");
        assertThat(analysisJob.analysisImages()).extracting(AnalysisImage::sha256).containsExactly("ab".repeat(32), "cd".repeat(32));
        assertThat(analysisJob.analysisImages()).extracting(AnalysisImage::requestId).doesNotHaveDuplicates();
        assertThat(analysisJob.analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsOnlyNulls();
        assertThat(analysisJob.submittedAt().toInstant()).isEqualTo(SUBMITTED_AT);
        assertThat(analysisJob.expiresAt()).isNull();
    }

    @Test
    void reusesTheRequestIdForTheSamePhotoAndHintAndDropsRepeatedKeys() {
        issue("first", "ab");
        AnalysisJob firstAnalysisJob = analysisJobSubmitService.submit(7L, List.of("first", "first"), "RECEIPT");
        AnalysisJob repeatedAnalysisJob = analysisJobSubmitService.submit(7L, List.of("first"), "RECEIPT");
        AnalysisJob otherHintAnalysisJob = analysisJobSubmitService.submit(7L, List.of("first"), "PRODUCT");
        assertThat(firstAnalysisJob.analysisImages()).hasSize(1);
        assertThat(repeatedAnalysisJob.id()).isNotEqualTo(firstAnalysisJob.id());
        assertThat(repeatedAnalysisJob.analysisImages().getFirst().requestId()).isEqualTo(firstAnalysisJob.analysisImages().getFirst().requestId());
        assertThat(otherHintAnalysisJob.analysisImages().getFirst().requestId()).isNotEqualTo(firstAnalysisJob.analysisImages().getFirst().requestId());
    }

    @Test
    void separatesTheSamePhotoWhenTheSelectedRefrigeratorChanges() {
        issue("first", "ab");
        AnalysisJob first = analysisJobSubmitService.submit(7L, List.of("first"), "AUTO");
        when(refrigerator.getId()).thenReturn(12L);
        AnalysisJob moved = analysisJobSubmitService.submit(7L, List.of("first"), "AUTO");
        assertThat(moved.refrigeratorId()).isEqualTo(12L);
        assertThat(moved.analysisImages().getFirst().requestId()).isNotEqualTo(first.analysisImages().getFirst().requestId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "other-owner", "profile", "expired"})
    void validatesEveryImageBeforeAcceptingAny(String objectKey) {
        issue("first", "ab");
        if (!objectKey.equals("missing")) {
            imageUploadMetadataStore.save(objectKey.equals("other-owner") ? 8L : 7L,
                    objectKey.equals("profile") ? ImageUploadPurpose.PROFILE : ImageUploadPurpose.ANALYSIS, objectKey, "cd".repeat(32));
        }
        if (objectKey.equals("expired")) { when(clock.instant()).thenReturn(SUBMITTED_AT.plus(UPLOAD_TTL)); }
        assertThatThrownBy(() -> analysisJobSubmitService.submit(7L, List.of("first", objectKey), null)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("IMAGE-422-001");
        verifyNoInteractions(analysisJobAcceptance);
    }

    @Test
    void normalizesTheDefaultHintBeforeAcceptance() {
        issue("first", "ab");
        AnalysisJob analysisJob = analysisJobSubmitService.submit(7L, List.of("first"), null);
        verify(analysisJobAcceptance).accept(7L, 11L, "AUTO", analysisJob.analysisImages());
    }

    @Test
    void propagatesFailureWhenTheAcceptanceTransactionCannotCommit() {
        issue("first", "ab");
        when(analysisJobAcceptance.accept(any(), any(), anyString(), anyList())).thenThrow(new DataAccessResourceFailureException("mysql"));
        assertThatThrownBy(() -> analysisJobSubmitService.submit(7L, List.of("first"), null)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private void issue(String objectKey, String sha256Prefix) { imageUploadMetadataStore.save(7L, ImageUploadPurpose.ANALYSIS, objectKey, sha256Prefix.repeat(32)); }
}
