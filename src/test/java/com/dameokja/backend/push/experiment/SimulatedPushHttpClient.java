package com.dameokja.backend.push.experiment;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;

// WebPushSender가 사용하는 HTTP 클라이언트다. 네트워크 연결 없이 설정한 응답을 돌려준다.
final class SimulatedPushHttpClient extends CloseableHttpClient {
    private final int responseStatus;
    private final int responseDelayMs;
    private final AtomicInteger requestCount = new AtomicInteger();

    SimulatedPushHttpClient(PushExperimentConfig config) {
        this.responseStatus = config.httpStatus();
        this.responseDelayMs = config.responseDelayMs();
    }

    @Override
    protected CloseableHttpResponse doExecute(HttpHost target, ClassicHttpRequest request, HttpContext context)
            throws InterruptedIOException {
        requireExperimentEndpoint(target, request);
        requestCount.incrementAndGet();
        waitForSimulatedResponse();
        // 상위 클래스의 execute()가 이 응답을 WebPushSender의 handler에 전달한다.
        return CloseableHttpResponse.adapt(new BasicClassicHttpResponse(responseStatus));
    }

    int requestCount() { return requestCount.get(); }

    private void requireExperimentEndpoint(HttpHost target, ClassicHttpRequest request) {
        if (target == null || !"fcm.googleapis.com".equals(target.getHostName())
                || !request.getPath().startsWith("/send/push-experiment/")) {
            throw new IllegalArgumentException("실험 구독만 사용할 수 있습니다.");
        }
    }

    private void waitForSimulatedResponse() throws InterruptedIOException {
        if (responseDelayMs == 0) {
            return;
        }
        try {
            Thread.sleep(responseDelayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("실험 HTTP 응답 대기가 중단됐습니다.");
        }
    }

    // 연결·스레드 등 클라이언트가 소유하는 자원이 없다.
    @Override
    public void close() {}

    @Override
    public void close(CloseMode closeMode) {}
}
