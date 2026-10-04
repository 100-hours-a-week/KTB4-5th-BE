package com.dameokja.backend.push.infrastructure;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpPost;

final class PushSendDeadline implements AutoCloseable {
    // 공유 DNS resolver에도 현재 발송의 기한을 전달해 늦게 끝난 조회가 연결로 이어지지 않게 한다.
    private static final ThreadLocal<PushSendDeadline> CURRENT = new ThreadLocal<>();
    private final HttpPost request;
    private final long expiresAtNanos;
    private final Thread cancellation;
    private boolean finished;
    private volatile boolean timedOut;

    PushSendDeadline(HttpPost request, Duration timeout) {
        this.request = request;
        this.expiresAtNanos = System.nanoTime() + timeout.toNanos();
        this.cancellation = Thread.ofVirtual().name("web-push-deadline").start(this::awaitExpiration);
        CURRENT.set(this);
    }

    private void awaitExpiration() {
        try {
            long remainingNanos = expiresAtNanos - System.nanoTime();
            if (remainingNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(remainingNanos);
            }
            expire();
        } catch (InterruptedException exception) {
            // 전송이 먼저 끝나면 취소 대기도 종료한다.
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void expire() {
        if (finished) {
            return;
        }
        finished = true;
        timedOut = true;
        request.cancel();
    }

    synchronized boolean finishWithinDeadline() {
        if (System.nanoTime() - expiresAtNanos >= 0) {
            expire();
        }
        finished = true;
        cancellation.interrupt();
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
            finishWithinDeadline();
        } finally {
            CURRENT.remove();
        }
    }
}
