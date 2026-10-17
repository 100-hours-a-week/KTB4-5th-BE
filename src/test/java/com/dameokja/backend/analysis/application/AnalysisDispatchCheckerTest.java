package com.dameokja.backend.analysis.application;

import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.CLOCK;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.LEASED;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.NOW;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.PROPERTIES;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.dispatch;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResponse;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class AnalysisDispatchCheckerTest {
    private final AnalysisDispatchRepository repository = mock(AnalysisDispatchRepository.class);
    private final AiAnalysisClient client = mock(AiAnalysisClient.class);
    private final AnalysisDispatchUpdater updater = mock(AnalysisDispatchUpdater.class);
    private final AnalysisDispatchFailure failure = mock(AnalysisDispatchFailure.class);
    private final AnalysisCompletionApplier applier = mock(AnalysisCompletionApplier.class);
    private final AnalysisDispatchChecker checker = new AnalysisDispatchChecker(repository, client, updater, failure, applier, PROPERTIES, CLOCK);

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING", "COMPLETED", "FAILED"})
    void keepsWaitingForTheCallbackWhileAiStillKnowsTheJob(String status) {
        checking(0);
        when(client.get("ai-1")).thenReturn(new AiImageAnalysisResponse("ai-1", status, null, null, null, null, null, null));
        checker.check(LEASED);
        verify(updater).extend(LEASED, status);
        verifyNoInteractions(failure);
    }

    @Test
    void finishesWithAParkedCallbackInsteadOfAskingAi() {
        checking(0);
        when(applier.applyParked("ai-1")).thenReturn(true);
        checker.check(LEASED);
        verifyNoInteractions(client, failure);
        verify(updater, never()).extend(any(), anyString());
    }

    @Test
    void keepsCheckingAiWhenApplyingAParkedCallbackFails() {
        checking(0);
        when(applier.applyParked("ai-1")).thenThrow(new DataAccessResourceFailureException("redis"));
        when(client.get("ai-1")).thenReturn(new AiImageAnalysisResponse("ai-1", "PROCESSING", null, null, null, null, null, null));
        checker.check(LEASED);
        verify(updater).extend(LEASED, "PROCESSING");
    }

    @Test
    void resendsTheSameRequestWhenAiLostTheJobUntilAttemptsRunOut() {
        checking(1);
        when(client.get("ai-1")).thenThrow(new RestClientResponseException("missing", 404, "missing", null, null, null));
        checker.check(LEASED);
        verify(updater).retryLater(LEASED, Duration.ZERO, true, AnalysisDispatchErrorCode.AI_JOB_LOST);

        AnalysisDispatch last = checking(2);
        checker.check(LEASED);
        verify(failure).fail(LEASED, last, AnalysisDispatchErrorCode.ANALYSIS_UNAVAILABLE);
    }

    @Test
    void doesNotTreatAnUnreachableAiAsALostJob() {
        checking(0);
        when(client.get("ai-1")).thenThrow(new ResourceAccessException("down"));
        checker.check(LEASED);
        verify(updater).extend(LEASED, "UNREACHABLE");
    }

    @Test
    void failsOnceTheOverallDeadlinePasses() {
        AnalysisDispatch dispatch = dispatch(AnalysisDispatchStatus.CHECKING, 0, NOW.minusMinutes(2), NOW);
        when(repository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(dispatch));
        checker.check(LEASED);
        verify(failure).fail(LEASED, dispatch, AnalysisDispatchErrorCode.ANALYSIS_EXPIRED);
        verifyNoInteractions(client);
    }

    private AnalysisDispatch checking(int attempts) {
        AnalysisDispatch dispatch = dispatch(AnalysisDispatchStatus.CHECKING, attempts);
        when(repository.findLeased(LEASED.id(), LEASED.token())).thenReturn(Optional.of(dispatch));
        return dispatch;
    }
}
