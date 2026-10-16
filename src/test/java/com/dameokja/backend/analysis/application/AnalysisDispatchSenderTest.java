package com.dameokja.backend.analysis.application;

import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.CLOCK;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.LEASED;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.NOW;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.PROPERTIES;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.dispatch;
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

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class AnalysisDispatchSenderTest {
    private final AnalysisDispatchRepository repository = mock(AnalysisDispatchRepository.class);
    private final AnalysisJobStore store = mock(AnalysisJobStore.class);
    private final AiAnalysisClient client = mock(AiAnalysisClient.class);
    private final AnalysisDispatchUpdater updater = mock(AnalysisDispatchUpdater.class);
    private final AnalysisDispatchFailure failure = mock(AnalysisDispatchFailure.class);
    private final AnalysisDispatchSender sender = new AnalysisDispatchSender(repository, store, client, updater, failure, PROPERTIES, CLOCK);

    @BeforeEach
    void storedJob() {
        when(store.find("job-1")).thenReturn(Optional.of(mock(AnalysisJob.class)));
    }

    @Test
    void registersTheAiIdBeforeRecordingAcceptanceWithTheSameRequestId() {
        AnalysisDispatch dispatch = leased(AnalysisDispatchStatus.DISPATCHING, 0);
        when(client.submit(any())).thenReturn(new AiImageAnalysisSubmitResponse("ai-1", "QUEUED", null, 1000));
        sender.send(LEASED);
        ArgumentCaptor<AiImageAnalysisSubmitRequest> request = ArgumentCaptor.forClass(AiImageAnalysisSubmitRequest.class);
        InOrder order = inOrder(client, store, updater);
        order.verify(client).submit(request.capture());
        order.verify(store).register("job-1", 7L, dispatch.getRequestId(), "ai-1");
        order.verify(updater).accept(LEASED, "ai-1");
        assertThat(request.getValue().requestId()).isEqualTo(dispatch.getRequestId());
        assertThat(request.getValue().image().sha256()).isEqualTo("ab".repeat(32));
    }

    @Test
    void waitsWithoutSpendingAnAttemptWhenAiQueueIsFull() {
        leased(AnalysisDispatchStatus.DISPATCHING, 1);
        when(client.submit(any())).thenThrow(new RestClientResponseException("busy", 429, "busy", null, null, null));
        sender.send(LEASED);
        verify(updater).retryLater(LEASED, Duration.ofSeconds(2), false, AnalysisDispatchErrorCode.AI_BUSY);
        verifyNoInteractions(failure);
        verify(store, never()).register(anyString(), any(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 422})
    void failsTheImageWhenAiRejectsTheRequest(int status) {
        AnalysisDispatch dispatch = leased(AnalysisDispatchStatus.DISPATCHING, 0);
        when(client.submit(any())).thenThrow(new RestClientResponseException("rejected", status, "rejected", null, null, null));
        sender.send(LEASED);
        verify(failure).fail(LEASED, dispatch, AnalysisDispatchErrorCode.ANALYSIS_REJECTED);
    }

    @Test
    void retriesConnectionFailuresWithBackoffUntilTheLastAttempt() {
        leased(AnalysisDispatchStatus.DISPATCHING, 1);
        when(client.submit(any())).thenThrow(new ResourceAccessException("timeout"));
        sender.send(LEASED);
        verify(updater).retryLater(LEASED, Duration.ofSeconds(2), true, AnalysisDispatchErrorCode.AI_UNAVAILABLE);

        AnalysisDispatch last = leased(AnalysisDispatchStatus.DISPATCHING, 2);
        sender.send(LEASED);
        verify(failure).fail(LEASED, last, AnalysisDispatchErrorCode.ANALYSIS_UNAVAILABLE);
    }

    @Test
    void failsAnExpiredImageWithoutCallingAi() {
        AnalysisDispatch dispatch = dispatch(AnalysisDispatchStatus.DISPATCHING, 0, NOW.minusMinutes(2), NOW);
        when(repository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(dispatch));
        sender.send(LEASED);
        verify(failure).fail(LEASED, dispatch, AnalysisDispatchErrorCode.ANALYSIS_EXPIRED);
        verifyNoInteractions(client);
    }

    @Test
    void waitsForAJobBeingCreatedButDropsAnOrphanAfterTheGracePeriod() {
        when(store.find("job-1")).thenReturn(Optional.empty());
        when(repository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(dispatch(AnalysisDispatchStatus.DISPATCHING, 0, NOW.minusSeconds(29), NOW.plusMinutes(1))));
        sender.send(LEASED);
        verify(updater).retryLater(LEASED, Duration.ofSeconds(1), false, AnalysisDispatchErrorCode.JOB_NOT_READY);

        when(repository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(dispatch(AnalysisDispatchStatus.DISPATCHING, 0, NOW.minusSeconds(30), NOW.plusMinutes(1))));
        sender.send(LEASED);
        verify(updater).fail(LEASED, AnalysisDispatchErrorCode.JOB_MISSING);
        verifyNoInteractions(client, failure);
    }

    @Test
    void resendsLaterWhenTheAcceptedIdCannotBeRegistered() {
        leased(AnalysisDispatchStatus.DISPATCHING, 0);
        when(client.submit(any())).thenReturn(new AiImageAnalysisSubmitResponse("ai-1", "QUEUED", null, 1000));
        doThrow(new DataAccessResourceFailureException("redis")).when(store).register(anyString(), any(), anyString(), eq("ai-1"));
        sender.send(LEASED);
        verify(updater).retryLater(LEASED, Duration.ofSeconds(1), false, AnalysisDispatchErrorCode.REGISTER_FAILED);
        verify(updater, never()).accept(any(), anyString());
    }

    @Test
    void skipsADispatchThatIsNoLongerLeasedForSending() {
        leased(AnalysisDispatchStatus.AWAITING_CALLBACK, 0);
        sender.send(LEASED);
        verifyNoInteractions(client, failure);
        verify(updater, never()).retryLater(any(), any(), anyBoolean(), anyString());
    }

    private AnalysisDispatch leased(AnalysisDispatchStatus status, int attempts) {
        AnalysisDispatch dispatch = dispatch(status, attempts);
        when(repository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(dispatch));
        return dispatch;
    }
}
