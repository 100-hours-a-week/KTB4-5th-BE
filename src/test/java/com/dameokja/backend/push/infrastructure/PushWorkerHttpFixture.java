package com.dameokja.backend.push.infrastructure;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class PushWorkerHttpFixture implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger responseStatus = new AtomicInteger(201);
    private volatile CountDownLatch responseGate = new CountDownLatch(0);
    private volatile CountDownLatch received = new CountDownLatch(1);

    PushWorkerHttpFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/push", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            received.countDown();
            try {
                if (!responseGate.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("HTTP response gate timeout");
                exchange.sendResponseHeaders(responseStatus.get(), -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/push"; }
    int requestCount() { return requests.get(); }
    void status(int status) { responseStatus.set(status); }
    void block(int expectedRequests) {
        responseGate = new CountDownLatch(1);
        received = new CountDownLatch(expectedRequests);
    }
    boolean awaitRequests() throws Exception { return received.await(5, TimeUnit.SECONDS); }
    void unblock() { responseGate.countDown(); }
    @Override public void close() {
        unblock();
        server.stop(0);
        executor.close();
    }
}
