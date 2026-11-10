package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.infrastructure.UserDeviceRepository.ActiveSubscriptionCounts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PushSubscriptionMetrics implements MeterBinder {
    private final UserDeviceRepository repository;
    private volatile ActiveSubscriptionCounts counts;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("push.subscriptions.active", this, PushSubscriptionMetrics::activeDevices)
                .description("Global active browser push subscriptions owned by active users")
                .register(registry);
        Gauge.builder("push.subscribers.active", this, PushSubscriptionMetrics::activeUsers)
                .description("Global distinct active users with an active push subscription")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${push.metrics.refresh-interval:60s}")
    public void refresh() {
        try {
            counts = repository.countActiveSubscriptions();
        } catch (RuntimeException exception) {
            counts = null;
            log.warn("활성 푸시 구독 지표 집계 실패", exception);
        }
    }

    private double activeDevices() {
        ActiveSubscriptionCounts snapshot = counts;
        return snapshot == null ? Double.NaN : snapshot.getDevices();
    }

    private double activeUsers() {
        ActiveSubscriptionCounts snapshot = counts;
        return snapshot == null ? Double.NaN : snapshot.getUsers();
    }
}
