package com.dameokja.backend.notification.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationExperimentEventsTest {
    @Test
    void recordsEveryConcurrentAcceptanceAndKeepsFirstAndLastTimes() {
        NotificationExperimentEvents events = new NotificationExperimentEvents();
        events.start();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < 20; worker++) {
                String endpoint = "test-" + worker;
                executor.submit(() -> {
                    for (int attempt = 0; attempt < 100; attempt++) {
                        events.recordAcceptance(endpoint);
                    }
                });
            }
        }
        Map<String, Object> result = events.snapshot();
        assertThat(result.get("stubAcceptances")).isEqualTo(2000);
        assertThat(result.get("uniqueDeviceEndpointsAccepted")).isEqualTo(20);
        assertThat((double) result.get("firstStubAcceptanceSeconds"))
                .isLessThanOrEqualTo((double) result.get("lastStubAcceptanceSeconds"));
    }

    @Test
    void clearsPreviousRunAndReportsMissingEventsAsNotApplicable() {
        NotificationExperimentEvents events = new NotificationExperimentEvents();
        events.start();
        events.recordGenerationCommit();
        events.recordAcceptance("test");

        events.start();

        Map<String, Object> result = events.snapshot();
        assertThat(result.get("generationTransactionsCommitted")).isEqualTo(0);
        assertThat(result.get("stubAcceptances")).isEqualTo(0);
        assertThat(result.get("uniqueDeviceEndpointsAccepted")).isEqualTo(0);
        assertThat(result.get("firstNotificationCommitObservedSeconds")).isEqualTo("N/A");
        assertThat(result.get("lastStubAcceptanceSeconds")).isEqualTo("N/A");
    }

    @Test
    void refusesToRecordAnUncommittedGenerationTransaction() {
        NotificationExperimentEvents events = new NotificationExperimentEvents();
        events.start();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(events::recordGenerationCommit).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(events.snapshot().get("generationTransactionsCommitted")).isEqualTo(0);
    }
}
