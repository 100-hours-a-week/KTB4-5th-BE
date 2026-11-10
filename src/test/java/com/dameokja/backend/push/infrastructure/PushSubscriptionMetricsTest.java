package com.dameokja.backend.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PushSubscriptionMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void gaugesSharePeriodicSnapshotWithoutQueryingDuringScrapes() {
        UserDeviceRepository repository = mock(UserDeviceRepository.class);
        PushSubscriptionMetrics metrics = new PushSubscriptionMetrics(repository);
        UserDeviceRepository.ActiveSubscriptionCounts counts = mock(UserDeviceRepository.ActiveSubscriptionCounts.class);
        when(counts.getDevices()).thenReturn(3L);
        when(counts.getUsers()).thenReturn(2L);
        when(repository.countActiveSubscriptions()).thenReturn(counts);
        metrics.bindTo(registry);
        assertThat(registry.get("push.subscriptions.active").gauge().value()).isNaN();
        metrics.refresh();
        assertThat(registry.get("push.subscriptions.active").gauge().value()).isEqualTo(3);
        assertThat(registry.get("push.subscribers.active").gauge().value()).isEqualTo(2);
        assertThat(registry.get("push.subscriptions.active").gauge().value()).isEqualTo(3);
        verify(repository).countActiveSubscriptions();
    }

    @Test
    void failedRefreshReportsUnknownAndNextSuccessfulRefreshRecovers() {
        UserDeviceRepository repository = mock(UserDeviceRepository.class);
        UserDeviceRepository.ActiveSubscriptionCounts empty = mock(UserDeviceRepository.ActiveSubscriptionCounts.class);
        when(repository.countActiveSubscriptions()).thenReturn(empty)
                .thenThrow(new IllegalStateException("database unavailable")).thenReturn(empty);
        PushSubscriptionMetrics metrics = new PushSubscriptionMetrics(repository);
        metrics.bindTo(registry);
        metrics.refresh();
        assertThat(registry.get("push.subscriptions.active").gauge().value()).isZero();
        metrics.refresh();
        assertThat(registry.get("push.subscriptions.active").gauge().value()).isNaN();
        assertThat(registry.get("push.subscribers.active").gauge().value()).isNaN();
        metrics.refresh();
        assertThat(registry.get("push.subscribers.active").gauge().value()).isZero();
    }
}
