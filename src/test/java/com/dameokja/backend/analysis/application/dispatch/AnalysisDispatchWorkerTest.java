package com.dameokja.backend.analysis.application.dispatch;

import static com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchFixture.LEASED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnalysisDispatchWorkerTest {
    private final AnalysisDispatchLeaseService analysisDispatchLeaseService = mock(AnalysisDispatchLeaseService.class);
    private final AnalysisDispatchSender analysisDispatchSender = mock(AnalysisDispatchSender.class);
    private final AnalysisDispatchChecker analysisDispatchChecker = mock(AnalysisDispatchChecker.class);
    private final AiAnalysisClient aiAnalysisClient = mock(AiAnalysisClient.class);
    private final AnalysisDispatchWorker analysisDispatchWorker = new AnalysisDispatchWorker(analysisDispatchLeaseService, analysisDispatchSender, analysisDispatchChecker, aiAnalysisClient);

    @AfterEach
    void shutdown() {
        analysisDispatchWorker.shutdown();
    }

    @Test
    void checksOverdueImagesButSendsNothingWhileAiIsNotReady() {
        when(analysisDispatchLeaseService.leaseExpired()).thenReturn(List.of(LEASED));
        when(aiAnalysisClient.ready()).thenReturn(false);
        analysisDispatchWorker.poll();
        verify(analysisDispatchChecker, timeout(1000)).check(LEASED);
        verify(analysisDispatchLeaseService, never()).leaseDue();
    }

    @Test
    void finishesExpiredQueuedImagesWhileAiIsNotReady() {
        when(analysisDispatchLeaseService.leaseExpiredPending()).thenReturn(List.of(LEASED));
        when(aiAnalysisClient.ready()).thenReturn(false);
        analysisDispatchWorker.poll();
        verify(analysisDispatchSender, timeout(1000)).send(LEASED);
    }

    @Test
    void sendsLeasedImagesWhenAiIsReady() {
        when(aiAnalysisClient.ready()).thenReturn(true);
        when(analysisDispatchLeaseService.leaseDue()).thenReturn(List.of(LEASED));
        analysisDispatchWorker.wakeUp();
        verify(analysisDispatchSender, timeout(1000)).send(LEASED);
    }
}
