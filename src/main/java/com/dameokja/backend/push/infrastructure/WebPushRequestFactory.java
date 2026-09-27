package com.dameokja.backend.push.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.jose4j.lang.JoseException;

// 라이브러리의 전송(PushService.sendAsync)은 전송마다 HTTP 클라이언트와 I/O 스레드를 새로 만든다.
// 그래서 암호화와 VAPID 서명을 붙인 요청 생성(prepareRequest)만 쓰고, 전송은 공유 HttpClient로 한다.
// 라이브러리 Notification은 알림 Entity와 이름이 같으므로 이 클래스 밖으로 노출하지 않는다.
public class WebPushRequestFactory extends AbstractPushService<WebPushRequestFactory> {

    public WebPushRequestFactory(String publicKey, String privateKey) throws GeneralSecurityException {
        super(publicKey, privateKey);
    }

    HttpRequest create(WebPushTarget target, String payloadJson, Duration ttl, Duration timeout)
            throws GeneralSecurityException, IOException, JoseException {
        // 라이브러리 기본값(aesgcm)은 초안 규격이므로 표준(RFC 8291)인 aes128gcm을 명시한다.
        nl.martijndwars.webpush.HttpRequest prepared =
                prepareRequest(toNotification(target, payloadJson, ttl), Encoding.AES128GCM);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(prepared.getUrl()))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(prepared.getBody()));
        prepared.getHeaders().forEach(builder::header);
        return builder.build();
    }

    private Notification toNotification(WebPushTarget target, String payloadJson, Duration ttl)
            throws GeneralSecurityException {
        return new Notification(target.endpoint(), target.p256dhKey(), target.authSecret(),
                payloadJson.getBytes(StandardCharsets.UTF_8), Math.toIntExact(ttl.toSeconds()));
    }
}
