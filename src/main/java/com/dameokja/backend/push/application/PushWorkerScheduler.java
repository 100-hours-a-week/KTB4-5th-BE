package com.dameokja.backend.push.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "push.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class PushWorkerScheduler {
    private final PushDispatchWorker worker;

    @Scheduled(fixedDelayString = "${push.dispatch.poll-interval:1s}")
    public void poll() {
        worker.poll();
    }
}
