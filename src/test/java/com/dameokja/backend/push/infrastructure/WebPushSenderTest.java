package com.dameokja.backend.push.infrastructure;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WebPushSenderTest {
    // 테스트 전용 P-256 키 쌍이다. 공개키는 구독 키(p256dh)로도 쓴다.
    private static final String PUBLIC_KEY =
            "BBsm6R4Q8Y7zDW6IP3LwAqDKguIYBLa83eH8r18Jd9ts8Dyt3xmcoSyL91wjkGIPymgWPJZPeol1iwrLafmJczY";
    private static final String PRIVATE_KEY = "CaYwQ9blK0k4N0J-5tPLIQzYBpSJj24S6sWadUP7wCg";
    private static final String AUTH_SECRET = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final WebPushTarget TARGET =
            new WebPushTarget("https://fcm.googleapis.com/send/abc", PUBLIC_KEY, AUTH_SECRET);
    private static final Duration TTL = Duration.ofHours(4);

    private final CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
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
    @CsvSource({"201, SUCCESS", "404, EXPIRED", "410, EXPIRED", "408, FAILED", "429, FAILED", "500, FAILED", "504, FAILED"})
    void mapsPushServiceStatus(int statusCode, WebPushResult expected) throws Exception {
        givenStatus(statusCode);

        assertThat(sender.send(TARGET, "{}", TTL)).isEqualTo(expected);
    }

    @Test
    void sendsOnCallingVirtualThreadWithoutSubmittingAnotherWorker() throws Exception {
        AtomicReference<Thread> caller = new AtomicReference<>();
        AtomicReference<Thread> executing = new AtomicReference<>();
        doAnswer(invocation -> {
            executing.set(Thread.currentThread());
            return 201;
        }).when(httpClient).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(calls.submit(() -> {
                caller.set(Thread.currentThread());
                return sender.send(TARGET, "{}", TTL);
            }).get()).isEqualTo(WebPushResult.SUCCESS);
        }
        assertThat(executing.get()).isSameAs(caller.get());
        assertThat(executing.get().isVirtual()).isTrue();
    }

    @Test
    void sendsEncryptedPayloadWithStandardEncodingTtlAndTimeout() throws Exception {
        givenStatus(201);
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);

        sender.send(TARGET, "{\"title\":\"알림\"}", TTL);

        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        HttpPost request = captor.getValue();
        assertThat(request.getUri()).isEqualTo(URI.create(TARGET.endpoint()));
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getConfig().getResponseTimeout()).isEqualTo(Timeout.of(WebPushSender.SEND_TIMEOUT));
        assertThat(request.getFirstHeader("Content-Encoding").getValue()).isEqualTo("aes128gcm");
        assertThat(request.getFirstHeader("TTL").getValue()).isEqualTo(String.valueOf(TTL.toSeconds()));
        assertThat(request.getFirstHeader("Authorization").getValue()).startsWith("vapid t=");
        assertThat(request.getEntity().getContentLength()).isPositive();
    }

    @Test
    void failsWhenRequestFails() throws Exception {
        doThrow(new IOException("connection reset")).when(httpClient).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));

        assertThat(sender.send(TARGET, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
    }

    @Test
    void failsWhenResponseTimesOut() throws Exception {
        doThrow(new SocketTimeoutException("timed out")).when(httpClient).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));

        assertThat(sender.send(TARGET, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
    }

    @Test
    void keepsInterruptFlagAndFailsWhenInterrupted() throws Exception {
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }).when(httpClient).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));

        assertThat(sender.send(TARGET, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void failsWithoutSendingWhenSubscriptionKeyIsMalformed() throws Exception {
        WebPushTarget malformed = new WebPushTarget(TARGET.endpoint(), "not-a-p256-key", AUTH_SECRET);

        assertThat(sender.send(malformed, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
        verify(httpClient, never()).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://fcm.googleapis.com/1", "https://169.254.169.254/latest/meta-data/",
            "https://[::1]/1", "https://fcm.googleapis.com.evil.com/1"})
    void rejectsStoredUnsafeEndpointWithoutSending(String endpoint) throws Exception {
        WebPushTarget unsafe = new WebPushTarget(endpoint, PUBLIC_KEY, AUTH_SECRET);

        assertThat(sender.send(unsafe, "{}", TTL)).isEqualTo(WebPushResult.FAILED);
        verify(httpClient, never()).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void hidesAuthSecretInToString() {
        assertThat(TARGET.toString()).doesNotContain(AUTH_SECRET);
    }

    @SuppressWarnings("unchecked")
    private void givenStatus(int statusCode) throws Exception {
        doAnswer(invocation -> {
            HttpClientResponseHandler<Integer> handler = invocation.getArgument(1);
            return handler.handleResponse(new BasicClassicHttpResponse(statusCode));
        }).when(httpClient).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
    }
}
