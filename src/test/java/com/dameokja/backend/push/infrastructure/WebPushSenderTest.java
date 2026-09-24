package com.dameokja.backend.push.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.message.BasicHttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebPushSenderTest {
    // 테스트 전용 P-256 공개키(65바이트)와 16바이트 auth 값이다.
    private static final String P256DH_KEY =
            "BBsm6R4Q8Y7zDW6IP3LwAqDKguIYBLa83eH8r18Jd9ts8Dyt3xmcoSyL91wjkGIPymgWPJZPeol1iwrLafmJczY";
    private static final String AUTH_SECRET = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final WebPushTarget TARGET =
            new WebPushTarget("https://push.example.com/send/abc", P256DH_KEY, AUTH_SECRET);

    private final PushService pushService = mock(PushService.class);
    private final WebPushSender sender = new WebPushSender(pushService);

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @ParameterizedTest
    @CsvSource({"201, SUCCESS", "404, EXPIRED", "410, EXPIRED", "429, FAILED", "500, FAILED"})
    void mapsPushServiceStatus(int statusCode, WebPushResult expected) throws Exception {
        givenResponse(CompletableFuture.completedFuture(response(statusCode)));

        assertThat(sender.send(TARGET, "{}")).isEqualTo(expected);
    }

    @Test
    void sendsPayloadWithStandardEncoding() throws Exception {
        givenResponse(CompletableFuture.completedFuture(response(201)));
        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);

        sender.send(TARGET, "{\"title\":\"알림\"}");

        verify(pushService).sendAsync(notification.capture(), eq(Encoding.AES128GCM));
        assertThat(notification.getValue().getEndpoint()).isEqualTo(TARGET.endpoint());
        assertThat(notification.getValue().getPayload())
                .isEqualTo("{\"title\":\"알림\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void failsWhenRequestFails() throws Exception {
        givenResponse(CompletableFuture.failedFuture(new ExecutionException(new RuntimeException())));

        assertThat(sender.send(TARGET, "{}")).isEqualTo(WebPushResult.FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelsAndFailsWhenResponseTimesOut() throws Exception {
        Future<HttpResponse> pending = mock(Future.class);
        when(pending.get(WebPushSender.SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        givenResponse(pending);

        assertThat(sender.send(TARGET, "{}")).isEqualTo(WebPushResult.FAILED);
        verify(pending).cancel(true);
    }

    @Test
    void failsWithoutSendingWhenSubscriptionKeyIsMalformed() throws Exception {
        WebPushTarget malformed = new WebPushTarget(TARGET.endpoint(), "not-a-p256-key", AUTH_SECRET);

        assertThat(sender.send(malformed, "{}")).isEqualTo(WebPushResult.FAILED);
        verify(pushService, never()).sendAsync(any(Notification.class), any(Encoding.class));
    }

    @Test
    void hidesAuthSecretInToString() {
        assertThat(TARGET.toString()).doesNotContain(AUTH_SECRET);
    }

    private void givenResponse(Future<HttpResponse> response) throws Exception {
        when(pushService.sendAsync(any(Notification.class), eq(Encoding.AES128GCM))).thenReturn(response);
    }

    private HttpResponse response(int statusCode) {
        return new BasicHttpResponse(HttpVersion.HTTP_1_1, statusCode, null);
    }
}
