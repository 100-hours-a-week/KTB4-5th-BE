package com.dameokja.backend.push.infrastructure;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpPost;

final class PushSendDeadline implements AutoCloseable {
    // 공유 DNS resolver에도 현재 발송의 기한을 전달해 늦게 끝난 조회가 연결로 이어지지 않게 한다.
    private static final ThreadLocal<PushSendDeadline> CURRENT = new ThreadLocal<>();
    private final HttpPost request;
    private final long expiresAtNanos;
    private final Runnable onTimeout;
    private final ScheduledFuture<?> cancellation;
    private boolean finished;
    private volatile boolean timedOut;

    PushSendDeadline(HttpPost request, ScheduledExecutorService timer, Duration timeout, Runnable onTimeout) {
        this.request = request;
        this.expiresAtNanos = System.nanoTime() + timeout.toNanos();
        this.onTimeout = onTimeout;
        this.cancellation = timer.schedule(this::expire, timeout.toNanos(), TimeUnit.NANOSECONDS);
        CURRENT.set(this);
    }

    private synchronized void expire() {
        if (finished) {
            return;
        }
        finished = true;
        timedOut = true;
        request.cancel();
        onTimeout.run();
    }

    synchronized boolean finish() {
        if (System.nanoTime() - expiresAtNanos >= 0) {
            expire();
        }
        finished = true;
        cancellation.cancel(false);
        return !timedOut;
    }

    static void check() throws SocketTimeoutException {
        PushSendDeadline deadline = CURRENT.get();
        if (deadline != null && (deadline.timedOut || System.nanoTime() - deadline.expiresAtNanos >= 0)) {
            deadline.expire();
            throw new SocketTimeoutException("푸시 발송 기한을 초과했습니다.");
        }
    }

    @Override
    public void close() {
        try {
            finish();
        } finally {
            CURRENT.remove();
        }
    }
}
