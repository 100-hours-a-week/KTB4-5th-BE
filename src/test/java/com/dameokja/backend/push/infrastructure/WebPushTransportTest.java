package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.infrastructure.LocalPushServer.StalledResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebPushTransportTest {
    private static final Duration TEST_WAIT = Duration.ofSeconds(15);
    private static final Duration IO_FALLBACK = Duration.ofSeconds(20);

    @ParameterizedTest(name = "{0}")
    @EnumSource(StalledResponse.class)
    void cancelsStalledConnectionAndKeepsSharedClientUsable(StalledResponse stalledResponse) throws Exception {
        // 라이브러리의 읽기 타임아웃이 요청 취소보다 먼저 통신을 끝내지 않도록 더 길게 설정한다.
        try (LocalPushServer server = new LocalPushServer(stalledResponse);
                CloseableHttpClient client = HttpClients.custom().disableAutomaticRetries().setDefaultRequestConfig(
                        RequestConfig.custom().setResponseTimeout(Timeout.of(IO_FALLBACK)).build()).build();
                ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpPost request = new HttpPost(server.url("/stall"));
            // URL 정책은 기존 테스트에서 검증한다. 이 테스트에서는 로컬 서버로 통신 결과만 관찰한다.
            WebPushRequestFactory factory = mock(WebPushRequestFactory.class);
            when(factory.create(any(), any(), any(), any())).thenReturn(request);
            WebPushSender sender = new WebPushSender(factory, client);

            Future<WebPushResult> result = calls.submit(() -> sender.send(
                    new WebPushTarget("test", "test", "test"), "{}", Duration.ofMinutes(1)));
            assertThat(server.awaitStalledRequestReceived()).isTrue();
            // 테스트가 무한히 기다리지 않기 위한 상한이며, 전체 작업의 엄격한 반환 기한은 아니다.
            assertThat(result.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(WebPushResult.FAILED);

            assertThat(request.isCancelled()).isTrue();
            assertThat(server.awaitStalledConnectionClosed()).isTrue();

            int status = client.execute(new HttpPost(server.url("/healthy")), response -> response.getCode());
            assertThat(status).isEqualTo(201);
            server.awaitSuccessfulCompletion();
        }
    }
}
