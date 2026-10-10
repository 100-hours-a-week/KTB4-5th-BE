package com.dameokja.backend.auth.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

final class MockKakaoServer implements AutoCloseable {
    private final HttpServer server;
    boolean verified;
    int tokenRequests;
    int userRequests;
    String tokenBody;
    String authorization;
    String tokenMethod;
    String tokenContentType;

    MockKakaoServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", this::token);
            server.createContext("/userinfo", this::user);
            server.start();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    void reset() {
        verified = true;
        tokenRequests = 0;
        userRequests = 0;
    }

    String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

    private void token(HttpExchange exchange) throws IOException {
        tokenRequests++;
        tokenMethod = exchange.getRequestMethod();
        tokenContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        tokenBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean invalid = tokenBody.contains("code=bad-code");
        reply(exchange, invalid ? 400 : 200, invalid ? "{\"error\":\"invalid_grant\"}" :
                "{\"access_token\":\"provider-access\",\"refresh_token\":\"provider-refresh\",\"token_type\":\"bearer\",\"expires_in\":3600}");
    }

    private void user(HttpExchange exchange) throws IOException {
        userRequests++;
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        reply(exchange, 200, "{\"id\":123,\"kakao_account\":{\"email\":\"member@example.com\",\"is_email_valid\":true,\"is_email_verified\":" + verified + "}}");
    }

    private void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() { server.stop(0); }
}
