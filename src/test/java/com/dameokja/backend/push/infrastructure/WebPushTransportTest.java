package com.dameokja.backend.push.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebPushTransportTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(WebPushSender.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.getThreadName();
            super.append(event);
        }
    };

    @BeforeEach
    void attachLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesStalledSocketAtTenSecondsAndKeepsSharedClientUsable(boolean receivedHeaders) throws Exception {
        try (LocalPushServer server = new LocalPushServer(receivedHeaders);
                CloseableHttpClient client = HttpClients.custom().disableAutomaticRetries().build()) {
            HttpPost request = new HttpPost(server.url("/stall"));
            WebPushRequestFactory factory = mock(WebPushRequestFactory.class);
            when(factory.create(any(), any(), any(), any())).thenReturn(request);
            WebPushSender sender = new WebPushSender(factory, client);
            AtomicReference<WebPushResult> result = new AtomicReference<>();
            long started = System.nanoTime();
            Thread caller = Thread.ofVirtual().name("push-transport-test").start(() -> result.set(sender.send(new WebPushTarget("test", "test", "test"), "{}", Duration.ofMinutes(1))));
            assertThat(server.received.await(5, TimeUnit.SECONDS)).isTrue();
            caller.join(Duration.ofSeconds(15));
            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isEqualTo(WebPushResult.FAILED);
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(9500L, 15000L);
            assertThat(request.isCancelled()).isTrue();
            assertThat(server.disconnected.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("status_code=" + (receivedHeaders ? "201" : "none"), "outcome=timeout", "error_type=deadline_exceeded");
                assertThat(event.getThreadName()).isEqualTo(caller.getName());
            });
            int status = client.execute(new HttpPost(server.url("/healthy")), response -> response.getCode());
            assertThat(status).isEqualTo(201);
        }
    }

    private static final class LocalPushServer implements AutoCloseable {
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final ServerSocket server = new ServerSocket(0, 10, InetAddress.ofLiteral("127.0.0.1"));
        private final boolean receivedHeaders;
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);

        private LocalPushServer(boolean receivedHeaders) throws IOException {
            this.receivedHeaders = receivedHeaders;
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
                    if (receivedHeaders) {
                        writeResponse(socket, 1);
                    }
                    received.countDown();
                    awaitDisconnect(reader);
                } else {
                    writeResponse(socket, 0);
                }
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private void writeResponse(Socket socket, int contentLength) throws IOException {
            String headers = "HTTP/1.1 201 Created\r\nContent-Length: " + contentLength + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
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
