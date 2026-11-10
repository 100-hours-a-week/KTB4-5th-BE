package com.dameokja.backend.notification.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationSseMetrics implements MeterBinder {
    private final NotificationSseConnections connections;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("sse.connections.active", connections, NotificationSseConnections::activeConnectionCount)
                .description("Number of active SSE connections on this application instance")
                .register(registry);
    }
}
