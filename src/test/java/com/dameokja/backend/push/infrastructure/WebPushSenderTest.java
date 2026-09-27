package com.dameokja.backend.push.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Duration;
import java.util.Optional;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebPushSenderTest {
    // 테스트 전용 P-256 키 쌍이다. 공개키는 구독 키(p256dh)로도 쓴다.
    private static final String PUBLIC_KEY =
            "BBsm6R4Q8Y7zDW6IP3LwAqDKguIYBLa83eH8r18Jd9ts8Dyt3xmcoSyL91wjkGIPymgWPJZPeol1iwrLafmJczY";
    private static final String PRIVATE_KEY = "CaYwQ9blK0k4N0J-5tPLIQzYBpSJj24S6sWadUP7wCg";
    private static final String AUTH_SECRET = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final WebPushTarget TARGET =
            new WebPushTarget("https://push.example.com/send/abc", PUBLIC_KEY, AUTH_SECRET);
    private static final Duration TTL = Duration.ofHours(4);

    private final HttpClient httpClient = mock(HttpClient.class);
    private WebPushSender sender;

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void setUp() throws GeneralSecurityException {
        sender = new WebPushSender(new WebPushRequestFactory(PUBLIC_KEY, PRIVATE_KEY), httpClient);
    }

    @ParameterizedTest
    @CsvSource({"201, SUCCESS", "404, EXPIRED", "410, EXPIRED", "429, FAILED", "500, FAILED"})
    void mapsPushServiceStatus(int statusCode, WebPushResult expected) throws Exception {
        givenStatus(statusCode);

        assertThat(sender.send(TARGET, "{}", TTL)).isEqualTo(expected);
    }

    @Test
    void sendsEncryptedPayloadWithStandardEncodingTtlAndTimeout() throws Exception {
        givenStatus(201);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);

        sender.send(TARGET, "{\"title\":\"알림\"}", TTL);

        verify(httpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.uri()).isEqualTo(URI.create(TARGET.endpoint()));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.timeout()).contains(WebPushSender.SEND_TIMEOUT);
        assertThat(request.headers().firstValue("Content-Encoding")).contains("aes128gcm");
        assertThat(request.headers().firstValue("TTL")).contains(String.valueOf(TTL.toSeconds()));
        assertThat(request.headers().firstValue("Authorization")).hasValueSatisfying(
                value -> assertThat(value).startsWith("vapid t="));
        assertThat(request.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength))
                .hasValueSatisfying(length -> assertThat(length).isPositive());
    }

    @Test
    void failsWhenRequestFails() throws Exception {
        doThrow(new IOException("connection reset")).when(httpClient).send(any(HttpRequest.class), any());

        assertThat(sender.send(TARGET, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
    }

    @Test
    void failsWhenResponseTimesOut() throws Exception {
        doThrow(new HttpTimeoutException("timed out")).when(httpClient).send(any(HttpRequest.class), any());

        assertThat(sender.send(TARGET, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
    }

    @Test
    void keepsInterruptFlagAndFailsWhenInterrupted() throws Exception {
        doThrow(new InterruptedException()).when(httpClient).send(any(HttpRequest.class), any());

        assertThat(sender.send(TARGET, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void failsWithoutSendingWhenSubscriptionKeyIsMalformed() throws Exception {
        WebPushTarget malformed = new WebPushTarget(TARGET.endpoint(), "not-a-p256-key", AUTH_SECRET);

        assertThat(sender.send(malformed, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
        verify(httpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void hidesAuthSecretInToString() {
        assertThat(TARGET.toString()).doesNotContain(AUTH_SECRET);
    }

    @SuppressWarnings("unchecked")
    private void givenStatus(int statusCode) throws Exception {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());
    }
}
