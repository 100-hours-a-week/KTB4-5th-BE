package com.dameokja.backend.analysis.application;

import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.LEASED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnalysisDispatchWorkerTest {
    private final AnalysisDispatchLeaseService leases = mock(AnalysisDispatchLeaseService.class);
    private final AnalysisDispatchSender sender = mock(AnalysisDispatchSender.class);
    private final AnalysisDispatchChecker checker = mock(AnalysisDispatchChecker.class);
    private final AiAnalysisClient client = mock(AiAnalysisClient.class);
    private final AnalysisDispatchWorker worker = new AnalysisDispatchWorker(leases, sender, checker, client);

    @AfterEach
    void shutdown() {
        worker.shutdown();
    }

    @Test
    void checksOverdueImagesButSendsNothingWhileAiIsNotReady() {
        when(leases.leaseExpired()).thenReturn(List.of(LEASED));
        when(client.ready()).thenReturn(false);
        worker.poll();
        verify(checker, timeout(1000)).check(LEASED);
        verify(leases, never()).leaseDue();
    }

    @Test
    void sendsLeasedImagesWhenAiIsReady() {
        when(client.ready()).thenReturn(true);
        when(leases.leaseDue()).thenReturn(List.of(LEASED));
        worker.wakeUp();
        verify(sender, timeout(1000)).send(LEASED);
    }
}
