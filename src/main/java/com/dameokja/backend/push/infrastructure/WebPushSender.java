package com.dameokja.backend.push.infrastructure;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.jose4j.lang.JoseException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebPushSender {
    // 발송 시작 10초 후 요청의 연결을 취소한다. OS DNS 조회 자체는 즉시 중단되지 않을 수 있다.
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final WebPushRequestFactory webPushRequestFactory;
    private final CloseableHttpClient webPushHttpClient;
    private final ScheduledExecutorService webPushDeadlineTimer;

    // ttl은 기기가 꺼져 있을 때 푸시 서비스가 메시지를 보관하는 기간이다. (RFC 8030)
    // 라이브러리 기본값(28일)을 쓰면 발송 기한이 지난 알림이 뒤늦게 도착하므로 호출하는 쪽이 정한다.
    public WebPushResult send(WebPushTarget target, String payloadJson, Duration ttl) {
        if (Thread.currentThread().isInterrupted()) {
            return WebPushResult.FAILED;
        }
        HttpPost request;
        try {
            request = webPushRequestFactory.create(target, payloadJson, ttl, SEND_TIMEOUT);
        } catch (GeneralSecurityException | IOException | JoseException | IllegalArgumentException exception) {
            log.warn("Web Push 요청을 만들지 못했습니다. target={}", target, exception);
            return WebPushResult.FAILED;
        }
        return send(request, target);
    }

    private WebPushResult send(HttpPost request, WebPushTarget target) {
        long startedNanos = System.nanoTime();
        AtomicInteger responseCode = new AtomicInteger();
        try (PushSendDeadline deadline = new PushSendDeadline(request, webPushDeadlineTimer, SEND_TIMEOUT,
                () -> logSendResult("timeout", responseCode.get(), startedNanos, "deadline_exceeded"))) {
            try {
                int statusCode = webPushHttpClient.execute(request, response -> {
                    responseCode.set(response.getCode());
                    PushSendDeadline.check();
                    return response.getCode();
                });
                if (!deadline.finish()) {
                    return WebPushResult.FAILED;
                }
                logSendResult("http_response", statusCode, startedNanos, "none");
                return WebPushResult.fromStatus(statusCode);
            } catch (IOException exception) {
                if (deadline.finish()) {
                    logSendResult(failureOutcome(exception), responseCode.get(), startedNanos, exception.getClass().getSimpleName());
                    log.warn("Web Push 전송에 실패했습니다. target={}", target, exception);
                }
                return WebPushResult.FAILED;
            }
        }
    }

    private String failureOutcome(IOException exception) {
        if (Thread.currentThread().isInterrupted()) {
            return "interrupted";
        }
        return exception instanceof SocketTimeoutException || exception instanceof ConnectionRequestTimeoutException ? "timeout" : "io_error";
    }

    private void logSendResult(String outcome, int statusCode, long startedNanos, String errorType) {
        long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        log.info("event=web_push_send status_code={} duration_ms={} error_type={} outcome={}",
                statusCode == 0 ? "none" : Integer.toString(statusCode), durationMs, errorType, outcome);
    }
}
