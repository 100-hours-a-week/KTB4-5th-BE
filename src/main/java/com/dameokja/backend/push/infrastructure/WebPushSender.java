package com.dameokja.backend.push.infrastructure;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jose4j.lang.JoseException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebPushSender {
    // 요청 타임아웃은 연결 시간까지 포함하므로 연결부터 응답까지 전체를 제한한다.
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final WebPushRequestFactory webPushRequestFactory;
    private final HttpClient webPushHttpClient;

    // ttl은 기기가 꺼져 있을 때 푸시 서비스가 메시지를 보관하는 기간이다. (RFC 8030)
    // 라이브러리 기본값(28일)을 쓰면 발송 기한이 지난 알림이 뒤늦게 도착하므로 호출하는 쪽이 정한다.
    public WebPushResult send(WebPushTarget target, String payloadJson, Duration ttl) {
        HttpRequest request;
        try {
            request = webPushRequestFactory.create(target, payloadJson, ttl, SEND_TIMEOUT);
        } catch (GeneralSecurityException | IOException | JoseException | IllegalArgumentException exception) {
            log.warn("Web Push 요청을 만들지 못했습니다. target={}", target, exception);
            return WebPushResult.FAILED;
        }
        return send(request, target);
    }

    private WebPushResult send(HttpRequest request, WebPushTarget target) {
        try {
            HttpResponse<Void> response = webPushHttpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return WebPushResult.fromStatus(response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return WebPushResult.FAILED;
        } catch (IOException exception) {
            log.warn("Web Push 전송에 실패했습니다. target={}", target, exception);
            return WebPushResult.FAILED;
        }
    }
}
