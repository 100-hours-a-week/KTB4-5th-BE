package com.dameokja.backend.push.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.jose4j.lang.JoseException;
import org.springframework.stereotype.Component;

// 라이브러리 Notification은 알림 Entity와 이름이 같으므로 이 클래스 밖으로 노출하지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class WebPushSender {
    static final long SEND_TIMEOUT_SECONDS = 10;

    private final PushService pushService;

    // ttl은 기기가 꺼져 있을 때 푸시 서비스가 메시지를 보관하는 기간이다. (RFC 8030)
    // 라이브러리 기본값(28일)을 쓰면 발송 기한이 지난 알림이 뒤늦게 도착하므로 호출하는 쪽이 정한다.
    public WebPushResult send(WebPushTarget target, String payloadJson, Duration ttl) {
        Future<HttpResponse> response;
        try {
            // 라이브러리 기본값(aesgcm)은 초안 규격이므로 표준(RFC 8291)인 aes128gcm을 명시한다.
            response = pushService.sendAsync(toNotification(target, payloadJson, ttl), Encoding.AES128GCM);
        } catch (GeneralSecurityException | IOException | JoseException | IllegalArgumentException exception) {
            log.warn("Web Push 요청을 만들지 못했습니다. target={}", target, exception);
            return WebPushResult.FAILED;
        }
        return await(response, target);
    }

    private Notification toNotification(WebPushTarget target, String payloadJson, Duration ttl)
            throws GeneralSecurityException {
        return new Notification(target.endpoint(), target.p256dhKey(), target.authSecret(),
                payloadJson.getBytes(StandardCharsets.UTF_8), Math.toIntExact(ttl.toSeconds()));
    }

    private WebPushResult await(Future<HttpResponse> response, WebPushTarget target) {
        try {
            HttpResponse httpResponse = response.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return WebPushResult.fromStatus(httpResponse.getStatusLine().getStatusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            response.cancel(true);
            return WebPushResult.FAILED;
        } catch (ExecutionException | TimeoutException exception) {
            response.cancel(true);
            log.warn("Web Push 전송에 실패했습니다. target={}", target, exception);
            return WebPushResult.FAILED;
        }
    }
}
