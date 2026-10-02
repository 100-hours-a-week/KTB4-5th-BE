package com.dameokja.backend.push.infrastructure;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.jose4j.lang.JoseException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebPushSender {
    // DNS 조회·연결·응답 본문 폐기를 포함한 발송 전체를 제한한다.
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final WebPushRequestFactory webPushRequestFactory;
    private final CloseableHttpClient webPushHttpClient;
    private final ExecutorService webPushExecutor;

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
        return awaitSend(request, target);
    }

    private WebPushResult awaitSend(HttpPost request, WebPushTarget target) {
        Future<WebPushResult> pending = webPushExecutor.submit(() -> send(request, target));
        try {
            return pending.get(SEND_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return WebPushResult.FAILED;
        } catch (ExecutionException | TimeoutException exception) {
            log.warn("Web Push 발송이 완료되지 못했습니다. target={}", target, exception);
            return WebPushResult.FAILED;
        } finally {
            if (!pending.isDone()) {
                request.cancel();
                pending.cancel(true);
            }
        }
    }

    private WebPushResult send(HttpPost request, WebPushTarget target) {
        long startedNanos = System.nanoTime();
        try {
            int statusCode = webPushHttpClient.execute(request, response -> response.getCode());
            logSendResult(Integer.toString(statusCode), startedNanos, "none");
            return WebPushResult.fromStatus(statusCode);
        } catch (IOException exception) {
            logSendResult("none", startedNanos, exception.getClass().getSimpleName());
            log.warn("Web Push 전송에 실패했습니다. target={}", target, exception);
            return WebPushResult.FAILED;
        }
    }

    private void logSendResult(String statusCode, long startedNanos, String errorType) {
        long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        log.info("event=web_push_send status_code={} duration_ms={} error_type={}", statusCode, durationMs, errorType);
    }
}
