package com.dameokja.backend.analysis.application.dispatch;

import static com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchFixture.CLOCK;
import static com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchFixture.LEASED;
import static com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchFixture.NOW;
import static com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchFixture.PROPERTIES;
import static com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchFixture.dispatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionApplier;
import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class AnalysisDispatchSenderTest {
    private final AnalysisDispatchRepository analysisDispatchRepository = mock(AnalysisDispatchRepository.class);
    private final AnalysisJobStore analysisJobStore = mock(AnalysisJobStore.class);
    private final AiAnalysisClient aiAnalysisClient = mock(AiAnalysisClient.class);
    private final AnalysisDispatchUpdater analysisDispatchUpdater = mock(AnalysisDispatchUpdater.class);
    private final AnalysisDispatchFailure failure = mock(AnalysisDispatchFailure.class);
    private final AnalysisCompletionApplier analysisCompletionApplier = mock(AnalysisCompletionApplier.class);
    private final AnalysisDispatchSender analysisDispatchSender = new AnalysisDispatchSender(analysisDispatchRepository, analysisJobStore, aiAnalysisClient, analysisDispatchUpdater, failure,
            analysisCompletionApplier, PROPERTIES, CLOCK);

    @BeforeEach
    void storedJob() {
        when(analysisJobStore.find("job-1")).thenReturn(Optional.of(mock(AnalysisJob.class)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 17L)
    void registersTheAiIdBeforeRecordingAcceptanceWithTheSameRequestId(Long refrigeratorId) {
        AnalysisJob job = mock(AnalysisJob.class);
        when(job.refrigeratorId()).thenReturn(refrigeratorId);
        when(analysisJobStore.find("job-1")).thenReturn(Optional.of(job));
        AnalysisDispatch analysisDispatch = leased(AnalysisDispatchStatus.DISPATCHING, 0);
        when(aiAnalysisClient.submit(any())).thenReturn(new AiImageAnalysisSubmitResponse("ai-1", "QUEUED", null, 1000));
        analysisDispatchSender.send(LEASED);
        ArgumentCaptor<AiImageAnalysisSubmitRequest> request = ArgumentCaptor.forClass(AiImageAnalysisSubmitRequest.class);
        InOrder order = inOrder(aiAnalysisClient, analysisJobStore, analysisDispatchUpdater);
        order.verify(aiAnalysisClient).submit(request.capture());
        order.verify(analysisJobStore).register("job-1", 7L, analysisDispatch.getRequestId(), "ai-1");
        order.verify(analysisDispatchUpdater).accept(LEASED, "ai-1", false);
        assertThat(request.getValue().refrigeratorId()).isEqualTo(refrigeratorId);
        String body = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(request.getValue());
        assertThat(body.contains("refrigeratorId")).isEqualTo(refrigeratorId != null);
        assertThat(request.getValue().requestId()).isEqualTo(analysisDispatch.getRequestId());
        assertThat(request.getValue().image().sha256()).isEqualTo("ab".repeat(32));
    }

    @Test
    void appliesACallbackParkedBeforeRegistrationAfterRecordingAcceptance() {
        leased(AnalysisDispatchStatus.DISPATCHING, 1);
        when(aiAnalysisClient.submit(any())).thenReturn(new AiImageAnalysisSubmitResponse("ai-1", "COMPLETED", null, 1000));
        analysisDispatchSender.send(LEASED);
        InOrder order = inOrder(analysisJobStore, analysisDispatchUpdater, analysisCompletionApplier);
        order.verify(analysisJobStore).register(eq("job-1"), eq(7L), anyString(), eq("ai-1"));
        order.verify(analysisDispatchUpdater).accept(LEASED, "ai-1", true);
        order.verify(analysisCompletionApplier).applyParked("ai-1");
    }

    @Test
    void keepsTheAcceptanceWhenApplyingAParkedCallbackFails() {
        leased(AnalysisDispatchStatus.DISPATCHING, 1);
        when(aiAnalysisClient.submit(any())).thenReturn(new AiImageAnalysisSubmitResponse("ai-1", "COMPLETED", null, 1000));
        when(analysisCompletionApplier.applyParked("ai-1")).thenThrow(new DataAccessResourceFailureException("redis"));
        analysisDispatchSender.send(LEASED);
        verify(analysisDispatchUpdater).accept(LEASED, "ai-1", true);
        verify(analysisDispatchUpdater, never()).retryLater(any(), any(), anyBoolean(), any());
    }

    @Test
    void waitsWithoutSpendingAnAttemptWhenAiQueueIsFull() {
        leased(AnalysisDispatchStatus.DISPATCHING, 1);
        when(aiAnalysisClient.submit(any())).thenThrow(new RestClientResponseException("busy", 429, "busy", null, null, null));
        analysisDispatchSender.send(LEASED);
        verify(analysisDispatchUpdater).retryLater(LEASED, Duration.ofSeconds(2), false, AnalysisDispatchErrorCode.AI_BUSY);
        verifyNoInteractions(failure);
        verify(analysisJobStore, never()).register(anyString(), any(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 422})
    void failsTheImageWhenAiRejectsTheRequest(int status) {
        AnalysisDispatch analysisDispatch = leased(AnalysisDispatchStatus.DISPATCHING, 0);
        when(aiAnalysisClient.submit(any())).thenThrow(new RestClientResponseException("rejected", status, "rejected", null, null, null));
        analysisDispatchSender.send(LEASED);
        verify(failure).fail(LEASED, analysisDispatch, AnalysisFailureCode.ANALYSIS_REJECTED);
    }

    @Test
    void retriesConnectionFailuresWithBackoffUntilTheLastAttempt() {
        leased(AnalysisDispatchStatus.DISPATCHING, 1);
        when(aiAnalysisClient.submit(any())).thenThrow(new ResourceAccessException("timeout"));
        analysisDispatchSender.send(LEASED);
        verify(analysisDispatchUpdater).retryLater(LEASED, Duration.ofSeconds(2), true, AnalysisDispatchErrorCode.AI_UNAVAILABLE);

        AnalysisDispatch last = leased(AnalysisDispatchStatus.DISPATCHING, 2);
        analysisDispatchSender.send(LEASED);
        verify(failure).fail(LEASED, last, AnalysisFailureCode.ANALYSIS_UNAVAILABLE);
    }

    @Test
    void failsAnExpiredImageWithoutCallingAi() {
        AnalysisDispatch analysisDispatch = dispatch(AnalysisDispatchStatus.DISPATCHING, 0, NOW.minusMinutes(2), NOW);
        when(analysisDispatchRepository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(analysisDispatch));
        analysisDispatchSender.send(LEASED);
        verify(failure).fail(LEASED, analysisDispatch, AnalysisFailureCode.ANALYSIS_EXPIRED);
        verifyNoInteractions(aiAnalysisClient);
    }

    @Test
    void waitsForAJobBeingCreatedButDropsAnOrphanAfterTheGracePeriod() {
        when(analysisJobStore.find("job-1")).thenReturn(Optional.empty());
        when(analysisDispatchRepository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(dispatch(AnalysisDispatchStatus.DISPATCHING, 0, NOW.minusSeconds(29), NOW.plusMinutes(1))));
        analysisDispatchSender.send(LEASED);
        verify(analysisDispatchUpdater).retryLater(LEASED, Duration.ofSeconds(1), false, AnalysisDispatchErrorCode.JOB_NOT_READY);

        when(analysisDispatchRepository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(dispatch(AnalysisDispatchStatus.DISPATCHING, 0, NOW.minusSeconds(30), NOW.plusMinutes(1))));
        analysisDispatchSender.send(LEASED);
        verify(analysisDispatchUpdater).fail(LEASED, AnalysisDispatchErrorCode.JOB_MISSING);
        verifyNoInteractions(aiAnalysisClient, failure);
    }

    @Test
    void resendsLaterWhenTheAcceptedIdCannotBeRegistered() {
        leased(AnalysisDispatchStatus.DISPATCHING, 0);
        when(aiAnalysisClient.submit(any())).thenReturn(new AiImageAnalysisSubmitResponse("ai-1", "QUEUED", null, 1000));
        doThrow(new DataAccessResourceFailureException("redis")).when(analysisJobStore).register(anyString(), any(), anyString(), eq("ai-1"));
        analysisDispatchSender.send(LEASED);
        verify(analysisDispatchUpdater).retryLater(LEASED, Duration.ofSeconds(1), false, AnalysisDispatchErrorCode.REGISTER_FAILED);
        verify(analysisDispatchUpdater, never()).accept(any(), anyString(), anyBoolean());
    }

    @Test
    void skipsADispatchThatIsNoLongerLeasedForSending() {
        leased(AnalysisDispatchStatus.AWAITING_CALLBACK, 0);
        analysisDispatchSender.send(LEASED);
        verifyNoInteractions(aiAnalysisClient, failure);
        verify(analysisDispatchUpdater, never()).retryLater(any(), any(), anyBoolean(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 65})
    void validatesAiIdsBeforeRegisteringOrAccepting(int length) {
        leased(AnalysisDispatchStatus.DISPATCHING, 0);
        String analysisId = "a".repeat(length);
        when(aiAnalysisClient.submit(any())).thenReturn(new AiImageAnalysisSubmitResponse(analysisId, "QUEUED", null, 1000));
        analysisDispatchSender.send(LEASED);
        if (length == 64) {
            verify(analysisJobStore).register(eq("job-1"), eq(7L), anyString(), eq(analysisId));
            verify(analysisDispatchUpdater).accept(LEASED, analysisId, false);
        } else {
            verify(analysisJobStore, never()).register(anyString(), any(), anyString(), anyString());
            verify(analysisDispatchUpdater, never()).accept(any(), anyString(), anyBoolean());
            verify(analysisDispatchUpdater).retryLater(LEASED, Duration.ofSeconds(1), true, AnalysisDispatchErrorCode.AI_UNAVAILABLE);
            verifyNoInteractions(analysisCompletionApplier);
        }
    }

    private AnalysisDispatch leased(AnalysisDispatchStatus status, int attempts) {
        AnalysisDispatch analysisDispatch = dispatch(status, attempts);
        when(analysisDispatchRepository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(analysisDispatch));
        return analysisDispatch;
    }
}
