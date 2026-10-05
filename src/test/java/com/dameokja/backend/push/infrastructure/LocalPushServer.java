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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class LocalPushServer implements AutoCloseable {
    enum StalledResponse {
        NO_HEADERS,
        HEADERS_WITHOUT_BODY
    }

    private static final Duration TEST_WAIT = Duration.ofSeconds(5);
    private static final Duration SOCKET_WAIT = Duration.ofSeconds(20);
    private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"));
    private final CountDownLatch received = new CountDownLatch(1);
    private final CountDownLatch disconnected = new CountDownLatch(1);
    private final FutureTask<Void> exchange;
    private final Thread worker;
    private volatile Socket activeSocket;

    LocalPushServer(StalledResponse stalledResponse) throws IOException {
        exchange = new FutureTask<>(() -> {
            handleStalledRequest(stalledResponse);
            handleHealthyRequest();
            return null;
        });
        worker = Thread.ofVirtual().name("local-push-server").start(exchange);
    }

    String url(String path) {
        return "http://127.0.0.1:" + listener.getLocalPort() + path;
    }

    boolean awaitStalledRequestReceived() throws InterruptedException {
        return received.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitStalledConnectionClosed() throws InterruptedException {
        return disconnected.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    void awaitSuccessfulCompletion() throws Exception {
        exchange.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private Socket accept() throws IOException {
        Socket socket = listener.accept();
        activeSocket = socket;
        socket.setSoTimeout((int) SOCKET_WAIT.toMillis());
        return socket;
    }

    private BufferedReader readRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // 요청 본문이 없는 테스트이므로 헤더 끝까지만 읽으면 된다.
        }
        return reader;
    }

    private void handleStalledRequest(StalledResponse stalledResponse) throws IOException {
        try (Socket socket = accept()) {
            BufferedReader reader = readRequest(socket);
            if (stalledResponse == StalledResponse.HEADERS_WITHOUT_BODY) {
                // 본문 대기를 재현하기 위해 길이만 알리고 실제 본문은 보내지 않는다.
                writeResponse(socket, 1);
            }
            received.countDown();
            awaitDisconnect(reader);
        }
    }

    private void handleHealthyRequest() throws IOException {
        try (Socket socket = accept()) {
            readRequest(socket);
            writeResponse(socket, 0);
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
            // 연결 취소에 따라 EOF 대신 소켓 예외로 연결 종료가 관찰될 수 있다.
            disconnected.countDown();
        }
    }

    @Override
    public void close() throws Exception {
        listener.close();
        Socket socket = activeSocket;
        if (socket != null) {
            socket.close();
        }
        worker.join(TEST_WAIT);
    }
}
