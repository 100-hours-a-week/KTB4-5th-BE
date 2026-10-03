package com.dameokja.backend.push.infrastructure;

import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushSendDeadlineTest {
    private final HttpPost request = spy(new HttpPost("https://fcm.googleapis.com/send/test"));
    private final CountDownLatch cancelled = new CountDownLatch(1);
    private final AtomicReference<Thread> cancellingThread = new AtomicReference<>();

    @BeforeEach
    void observeCancellation() {
        doAnswer(invocation -> {
            cancellingThread.set(Thread.currentThread());
            Object result = invocation.callRealMethod();
            cancelled.countDown();
            return result;
        }).when(request).cancel();
    }

    private PushSendDeadline deadline() {
        return new PushSendDeadline(request, Duration.ofSeconds(1));
    }

    @Test
    void stopsCancellationWaitAfterEarlyCompletionAndClearsCurrentDeadline() throws Exception {
        try (PushSendDeadline deadline = deadline()) {
            assertThat(deadline.finish()).isTrue();
            PushSendDeadline.check();
        }
        assertThat(cancelled.await(1500, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(request.isCancelled()).isFalse();
        PushSendDeadline.check();
    }

    @Test
    void expiresOnVirtualThread() throws Exception {
        try (PushSendDeadline deadline = new PushSendDeadline(request, Duration.ofMillis(100))) {
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellingThread.get().isVirtual()).isTrue();
            assertThat(deadline.finish()).isFalse();
        }
    }

    @Test
    void retainsPermitWhileDnsIsBlockedAndRejectsLateResultBeforeSocketConnect() throws Exception {
        CountDownLatch dnsEntered = new CountDownLatch(1);
        CountDownLatch releaseDns = new CountDownLatch(1);
        DnsResolver delegate = mock(DnsResolver.class);
        when(delegate.resolve("fcm.googleapis.com")).thenAnswer(invocation -> {
            dnsEntered.countDown();
            releaseDns.await();
            return new InetAddress[]{InetAddress.ofLiteral("8.8.8.8")};
        });
        Socket socket = mock(Socket.class);
        DefaultHttpClientConnectionOperator operator = new DefaultHttpClientConnectionOperator(proxy -> socket, null, new PushDnsResolver(delegate), scheme -> null);
        Semaphore permit = new Semaphore(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> connectWithPermit(operator, permit, failure));
        try {
            assertThat(dnsEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(request.isCancelled()).isTrue();
            assertThat(permit.availablePermits()).isZero();
            assertThat(caller.isAlive()).isTrue();
        } finally {
            releaseDns.countDown();
            caller.join(Duration.ofSeconds(5));
        }
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(UnknownHostException.class);
        assertThat(permit.availablePermits()).isEqualTo(1);
        verify(socket, never()).connect(any(), anyInt());
    }

    private void connectWithPermit(DefaultHttpClientConnectionOperator operator, Semaphore permit, AtomicReference<Exception> failure) {
        permit.acquireUninterruptibly();
        try (PushSendDeadline ignored = deadline()) {
            operator.connect(mock(ManagedHttpClientConnection.class), new HttpHost("https", "fcm.googleapis.com", 443), null,
                    Timeout.ofSeconds(10), SocketConfig.DEFAULT, HttpClientContext.create());
        } catch (Exception exception) {
            failure.set(exception);
        } finally {
            permit.release();
        }
    }
}
