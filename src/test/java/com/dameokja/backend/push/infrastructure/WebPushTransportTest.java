package com.dameokja.backend.push.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebPushTransportTest {
    @Test
    void closesStalledSocketAtTenSecondsAndKeepsSharedClientUsable() throws Exception {
        try (LocalPushServer server = new LocalPushServer();
                ScheduledExecutorService timer = new WebPushConfig().webPushDeadlineTimer();
                CloseableHttpClient client = HttpClients.custom().disableAutomaticRetries().build()) {
            HttpPost request = new HttpPost(server.url("/stall"));
            WebPushRequestFactory factory = mock(WebPushRequestFactory.class);
            when(factory.create(any(), any(), any(), any())).thenReturn(request);
            WebPushSender sender = new WebPushSender(factory, client, timer);
            AtomicReference<WebPushResult> result = new AtomicReference<>();
            long started = System.nanoTime();
            Thread caller = Thread.ofVirtual().start(() -> result.set(sender.send(new WebPushTarget("test", "test", "test"), "{}", Duration.ofMinutes(1))));
            assertThat(server.received.await(5, TimeUnit.SECONDS)).isTrue();
            caller.join(Duration.ofSeconds(15));
            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isEqualTo(WebPushResult.FAILED);
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(9500L, 15000L);
            assertThat(request.isCancelled()).isTrue();
            assertThat(server.disconnected.await(5, TimeUnit.SECONDS)).isTrue();
            int status = client.execute(new HttpPost(server.url("/healthy")), response -> response.getCode());
            assertThat(status).isEqualTo(201);
        }
    }

    private static final class LocalPushServer implements AutoCloseable {
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final ServerSocket server = new ServerSocket(0, 10, InetAddress.ofLiteral("127.0.0.1"));
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);

        private LocalPushServer() throws IOException {
            workers.submit(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket socket = server.accept();
                        workers.submit(() -> handle(socket));
                    }
                } catch (IOException exception) {
                    if (!server.isClosed()) {
                        throw new IllegalStateException(exception);
                    }
                }
            });
        }

        private void handle(Socket socket) {
            try (socket) {
                socket.setSoTimeout(20000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String requestLine = reader.readLine();
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    // HTTP 요청 헤더의 끝까지 읽는다.
                }
                if (requestLine.contains("/stall")) {
                    received.countDown();
                    awaitDisconnect(reader);
                } else {
                    socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private void awaitDisconnect(BufferedReader reader) throws IOException {
            try {
                if (reader.read() == -1) {
                    disconnected.countDown();
                }
            } catch (SocketException exception) {
                if (!exception.getMessage().contains("reset")) {
                    throw exception;
                }
                disconnected.countDown();
            }
        }

        private String url(String path) {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        @Override
        public void close() throws IOException {
            server.close();
            workers.close();
        }
    }
}
