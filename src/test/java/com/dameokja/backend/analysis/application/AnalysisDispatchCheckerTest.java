package com.dameokja.backend.analysis.application;

import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.CLOCK;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.LEASED;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.NOW;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.PROPERTIES;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.dispatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResponse;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResult;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.global.exception.CustomException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class AnalysisDispatchCheckerTest {
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-10-17T03:00:00Z");
    private final AnalysisDispatchRepository analysisDispatchRepository = mock(AnalysisDispatchRepository.class);
    private final AiAnalysisClient aiAnalysisClient = mock(AiAnalysisClient.class);
    private final AnalysisDispatchUpdater analysisDispatchUpdater = mock(AnalysisDispatchUpdater.class);
    private final AnalysisDispatchFailure failure = mock(AnalysisDispatchFailure.class);
    private final AnalysisCompletionApplier analysisCompletionApplier = mock(AnalysisCompletionApplier.class);
    private final AnalysisCompletionValidator analysisCompletionValidator = new AnalysisCompletionValidator();
    private final AnalysisDispatchChecker analysisDispatchChecker = new AnalysisDispatchChecker(analysisDispatchRepository, aiAnalysisClient, analysisDispatchUpdater, failure,
            analysisCompletionApplier, analysisCompletionValidator, PROPERTIES, CLOCK);

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING"})
    void keepsWaitingForTheCallbackWhileAiIsStillWorking(String status) {
        checking(0);
        when(aiAnalysisClient.get("ai-1")).thenReturn(new AiImageAnalysisResponse("ai-1", status, null, null, null, null, null, null));
        analysisDispatchChecker.check(LEASED);
        verify(analysisDispatchUpdater).extend(LEASED, status);
        verifyNoInteractions(failure);
    }

    @Test
    void completesWithTheAiResultWhenTheCallbackNeverArrived() {
        checking(0);
        when(aiAnalysisClient.get("ai-1")).thenReturn(completed());
        analysisDispatchChecker.check(LEASED);
        ArgumentCaptor<AnalysisCompletion> completion = ArgumentCaptor.forClass(AnalysisCompletion.class);
        verify(analysisCompletionApplier).apply(eq("ai-1"), completion.capture());
        assertThat(completion.getValue().status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(completion.getValue().result().documentType()).isEqualTo(AnalysisDocumentType.OTHER);
        verify(analysisDispatchUpdater, never()).extend(any(), anyString());
        verifyNoInteractions(failure);
    }

    @Test
    void finishesWithTheAiFailureWhenTheCallbackNeverArrived() {
        checking(0);
        when(aiAnalysisClient.get("ai-1")).thenReturn(new AiImageAnalysisResponse("ai-1", "FAILED", null, null, null, COMPLETED_AT, null,
                new AiImageAnalysisResponse.Error("UNSUPPORTED_IMAGE", "지원하지 않는 이미지", false, null)));
        analysisDispatchChecker.check(LEASED);
        ArgumentCaptor<AnalysisCompletion> completion = ArgumentCaptor.forClass(AnalysisCompletion.class);
        verify(analysisCompletionApplier).apply(eq("ai-1"), completion.capture());
        assertThat(completion.getValue().error().code()).isEqualTo("UNSUPPORTED_IMAGE");
    }

    @Test
    void checksAgainLaterWhenSavingTheAiResultFails() {
        checking(0);
        when(aiAnalysisClient.get("ai-1")).thenReturn(completed());
        doThrow(new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE)).when(analysisCompletionApplier).apply(eq("ai-1"), any());
        analysisDispatchChecker.check(LEASED);
        verify(analysisDispatchUpdater).extend(LEASED, "COMPLETED");
        verifyNoInteractions(failure);
    }

    @Test
    void leavesTheRowToTheCallbackThatAlreadySavedAnotherResult() {
        checking(0);
        when(aiAnalysisClient.get("ai-1")).thenReturn(completed());
        doThrow(new CustomException(AnalysisExceptionCode.RESULT_CONFLICT)).when(analysisCompletionApplier).apply(eq("ai-1"), any());
        analysisDispatchChecker.check(LEASED);
        verify(analysisDispatchUpdater, never()).extend(any(), anyString());
        verifyNoInteractions(failure);
    }

    @Test
    void failsInsteadOfHoldingTheSlotWhenTheAiResultCannotBeUsed() {
        AnalysisDispatch analysisDispatch = checking(0);
        when(aiAnalysisClient.get("ai-1")).thenReturn(new AiImageAnalysisResponse("ai-1", "COMPLETED", null, null, null, COMPLETED_AT, null, null));
        analysisDispatchChecker.check(LEASED);
        verify(failure).fail(LEASED, analysisDispatch, AnalysisFailureCode.ANALYSIS_UNAVAILABLE);
        verify(analysisCompletionApplier, never()).apply(anyString(), any());
    }

    @Test
    void finishesWithAParkedCallbackInsteadOfAskingAi() {
        checking(0);
        when(analysisCompletionApplier.applyParked("ai-1")).thenReturn(true);
        analysisDispatchChecker.check(LEASED);
        verifyNoInteractions(aiAnalysisClient, failure);
        verify(analysisDispatchUpdater, never()).extend(any(), anyString());
    }

    @Test
    void keepsCheckingAiWhenApplyingAParkedCallbackFails() {
        checking(0);
        when(analysisCompletionApplier.applyParked("ai-1")).thenThrow(new DataAccessResourceFailureException("redis"));
        when(aiAnalysisClient.get("ai-1")).thenReturn(new AiImageAnalysisResponse("ai-1", "PROCESSING", null, null, null, null, null, null));
        analysisDispatchChecker.check(LEASED);
        verify(analysisDispatchUpdater).extend(LEASED, "PROCESSING");
    }

    @Test
    void resendsTheSameRequestWhenAiLostTheJobUntilAttemptsRunOut() {
        checking(1);
        when(aiAnalysisClient.get("ai-1")).thenThrow(new RestClientResponseException("missing", 404, "missing", null, null, null));
        analysisDispatchChecker.check(LEASED);
        verify(analysisDispatchUpdater).retryLater(LEASED, Duration.ZERO, true, AnalysisDispatchErrorCode.AI_JOB_LOST);

        AnalysisDispatch last = checking(2);
        analysisDispatchChecker.check(LEASED);
        verify(failure).fail(LEASED, last, AnalysisFailureCode.ANALYSIS_UNAVAILABLE);
    }

    @Test
    void doesNotTreatAnUnreachableAiAsALostJob() {
        checking(0);
        when(aiAnalysisClient.get("ai-1")).thenThrow(new ResourceAccessException("down"));
        analysisDispatchChecker.check(LEASED);
        verify(analysisDispatchUpdater).extend(LEASED, "UNREACHABLE");
    }

    @Test
    void failsOnceTheOverallDeadlinePasses() {
        AnalysisDispatch analysisDispatch = dispatch(AnalysisDispatchStatus.CHECKING, 0, NOW.minusMinutes(2), NOW);
        when(analysisDispatchRepository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(analysisDispatch));
        analysisDispatchChecker.check(LEASED);
        verify(failure).fail(LEASED, analysisDispatch, AnalysisFailureCode.ANALYSIS_EXPIRED);
        verifyNoInteractions(aiAnalysisClient);
    }

    private AiImageAnalysisResponse completed() {
        AiImageAnalysisResult result = new AiImageAnalysisResult(new AiImageAnalysisResult.DocumentType("OTHER", 0.9),
                new AiImageAnalysisResult.ImageQuality(0.9, List.of()), List.of(), null);
        return new AiImageAnalysisResponse("ai-1", "COMPLETED", "COMPLETED", null, null, COMPLETED_AT, result, null);
    }

    private AnalysisDispatch checking(int attempts) {
        AnalysisDispatch analysisDispatch = dispatch(AnalysisDispatchStatus.CHECKING, attempts);
        when(analysisDispatchRepository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(analysisDispatch));
        return analysisDispatch;
    }
}
