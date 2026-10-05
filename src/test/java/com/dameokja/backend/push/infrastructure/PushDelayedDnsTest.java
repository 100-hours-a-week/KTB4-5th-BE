package com.dameokja.backend.push.infrastructure;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushDelayedDnsTest {
    private static final Duration DEADLINE = Duration.ofSeconds(1);
    private static final Duration TEST_WAIT = Duration.ofSeconds(5);

    @Test
    void rejectsLateDnsResultBeforeSocketConnect() throws Exception {
        HttpPost request = mock(HttpPost.class);
        Socket socket = mock(Socket.class);
        CountDownLatch dnsStarted = new CountDownLatch(1);
        CountDownLatch releaseDns = new CountDownLatch(1);
        DnsResolver delegate = delayedDns(dnsStarted, releaseDns);
        DefaultHttpClientConnectionOperator operator = new DefaultHttpClientConnectionOperator(
                proxy -> socket, null, new PushDnsResolver(delegate), scheme -> null);
        FutureTask<IOException> connection = new FutureTask<>(() -> connect(operator, request));
        Thread caller = Thread.ofVirtual().start(connection);
        try {
            assertThat(dnsStarted.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            verify(request, timeout(TEST_WAIT.toMillis())).cancel();
        } finally {
            releaseDns.countDown();
            caller.join(TEST_WAIT);
        }
        assertThat(connection.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isInstanceOf(UnknownHostException.class);
        verify(socket, never()).connect(any(), anyInt());
    }

    private DnsResolver delayedDns(CountDownLatch dnsStarted, CountDownLatch releaseDns) throws Exception {
        DnsResolver delegate = mock(DnsResolver.class);
        when(delegate.resolve("fcm.googleapis.com")).thenAnswer(invocation -> {
            dnsStarted.countDown();
            releaseDns.await();
            return new InetAddress[]{InetAddress.ofLiteral("8.8.8.8")};
        });
        return delegate;
    }

    private IOException connect(DefaultHttpClientConnectionOperator operator, HttpPost request) {
        try (PushSendDeadline ignored = new PushSendDeadline(request, DEADLINE)) {
            // 소켓은 모킹해 외부 접속을 방지하고, 실제 Apache 연결 경로와 프로젝트 resolver를 실행한다.
            operator.connect(mock(ManagedHttpClientConnection.class), new HttpHost("https", "fcm.googleapis.com", 443), null,
                    Timeout.ofSeconds(10), SocketConfig.DEFAULT, HttpClientContext.create());
            return null;
        } catch (IOException exception) {
            return exception;
        }
    }
}
