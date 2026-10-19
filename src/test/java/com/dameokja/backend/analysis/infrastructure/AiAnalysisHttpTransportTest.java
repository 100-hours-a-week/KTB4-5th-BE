package com.dameokja.backend.analysis.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisHttpTransportTest {
    private static final String OBJECT_KEY = "uploads/analysis/7/image.png";
    private static final String SHA256 = "a".repeat(64);
    private final AtomicReference<String> upgradeHeader = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer httpServer;
    private HttpClient httpClient;
    private AiAnalysisClient aiAnalysisClient;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/ai/v1/analyses", this::acceptAnalysis);
        httpServer.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
        AiAnalysisProperties aiAnalysisProperties = new AiAnalysisProperties(baseUrl, "test-key", Duration.ofSeconds(1), Duration.ofSeconds(2));
        AiAnalysisConfig aiAnalysisConfig = new AiAnalysisConfig();
        httpClient = aiAnalysisConfig.aiAnalysisHttpClient(aiAnalysisProperties);
        aiAnalysisClient = aiAnalysisConfig.aiAnalysisClient(aiAnalysisProperties, httpClient);
    }

    @AfterEach
    void closeTransport() {
        httpClient.close();
        httpServer.stop(0);
    }

    @Test
    void sendsJsonBodyWithoutHttp2Upgrade() {
        aiAnalysisClient.submit(new AiImageAnalysisSubmitRequest("request-1", OBJECT_KEY, SHA256));

        assertThat(upgradeHeader.get()).isNull();
        JsonNode jsonNode = JsonMapper.builder().build().readTree(requestBody.get());
        assertThat(jsonNode.path("requestId").asText()).isEqualTo("request-1");
        assertThat(jsonNode.path("image").path("objectKey").asText()).isEqualTo(OBJECT_KEY);
        assertThat(jsonNode.path("image").path("sha256").asText()).isEqualTo(SHA256);
    }

    private void acceptAnalysis(HttpExchange httpExchange) throws IOException {
        try (httpExchange) {
            upgradeHeader.set(httpExchange.getRequestHeaders().getFirst("Upgrade"));
            requestBody.set(new String(httpExchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] responseBody = """
                    {"analysisId":"analysis-1","status":"QUEUED","submittedAt":"2026-10-09T01:00:00Z","pollAfterMs":1000}
                    """.getBytes(StandardCharsets.UTF_8);
            httpExchange.getResponseHeaders().set("Content-Type", "application/json");
            httpExchange.sendResponseHeaders(202, responseBody.length);
            httpExchange.getResponseBody().write(responseBody);
        }
    }
}
