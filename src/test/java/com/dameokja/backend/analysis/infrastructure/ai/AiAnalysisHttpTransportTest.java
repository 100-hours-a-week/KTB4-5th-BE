package com.dameokja.backend.analysis.infrastructure.ai;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.global.config.ai.AiClientConfig;
import com.dameokja.backend.global.config.ai.AiClientProperties;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.ingredient.domain.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer httpServer;
    private HttpClient httpClient;
    private AiAnalysisClient aiAnalysisClient;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/ai/v1/analyses", this::acceptAnalysis);
        httpServer.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
        AiClientProperties aiAnalysisProperties = new AiClientProperties(baseUrl, "test-key", Duration.ofSeconds(1), Duration.ofSeconds(2));
        AiClientConfig aiAnalysisConfig = new AiClientConfig();
        httpClient = aiAnalysisConfig.aiHttpClient(aiAnalysisProperties);
        aiAnalysisClient = aiAnalysisConfig.aiAnalysisClient(aiAnalysisProperties, httpClient);
    }

    @AfterEach
    void closeTransport() {
        httpClient.close();
        httpServer.stop(0);
    }

    @Test
    void sendsJsonBodyWithoutHttp2Upgrade() {
        aiAnalysisClient.submit(new AiImageAnalysisSubmitRequest("request-1", 7L, OBJECT_KEY, SHA256));

        assertThat(upgradeHeader.get()).isNull();
        JsonNode jsonNode = JsonMapper.builder().build().readTree(requestBody.get());
        assertThat(jsonNode.path("userId").asLong()).isEqualTo(7L);
        assertThat(jsonNode.has("refrigeratorId")).isFalse();
        assertThat(jsonNode.path("requestId").asText()).isEqualTo("request-1");
        assertThat(jsonNode.path("image").path("objectKey").asText()).isEqualTo(OBJECT_KEY);
        assertThat(jsonNode.path("image").path("sha256").asText()).isEqualTo(SHA256);
    }

    @Test
    void sendsBothItemsInOneHttpRequest() {
        AiAnalysisFeedbackRequest.Confirmed confirmed = AiAnalysisFeedbackRequest.Confirmed.userConfirmed("우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 2, null, WeightUnit.NONE, LocalDate.of(2026, 10, 31));
        AiAnalysisFeedbackRequest request = new AiAnalysisFeedbackRequest(17L, true, List.of(
                AiAnalysisFeedbackRequest.Item.corrected("item-1", confirmed), AiAnalysisFeedbackRequest.Item.corrected("item-2", confirmed)));
        assertThat(aiAnalysisClient.feedback("ai-123", request).status()).isEqualTo("RECORDED");
        assertThat(requests.get()).isEqualTo(1);
        JsonNode body = JsonMapper.builder().build().readTree(requestBody.get());
        assertThat(body.path("userId").asLong()).isEqualTo(17L);
        assertThat(body.has("refrigeratorId")).isFalse();
        assertThat(body.path("items").size()).isEqualTo(2);
        assertThat(body.path("items").get(0).path("itemId").asText()).isEqualTo("item-1");
        assertThat(body.path("items").get(1).path("itemId").asText()).isEqualTo("item-2");
    }

    private void acceptAnalysis(HttpExchange httpExchange) throws IOException {
        try (httpExchange) {
            requests.incrementAndGet();
            upgradeHeader.set(httpExchange.getRequestHeaders().getFirst("Upgrade"));
            requestBody.set(new String(httpExchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] responseBody = """
                    {"analysisId":"analysis-1","status":"QUEUED","submittedAt":"2026-10-09T01:00:00Z","pollAfterMs":1000}
                    """.getBytes(StandardCharsets.UTF_8);
            httpExchange.getResponseHeaders().set("Content-Type", "application/json");
            boolean feedback = httpExchange.getRequestURI().getPath().endsWith("/feedback");
            if (feedback) {
                responseBody = "{\"feedbackId\":\"fb-1\",\"status\":\"RECORDED\"}".getBytes(StandardCharsets.UTF_8);
            }
            httpExchange.sendResponseHeaders(feedback ? 201 : 202, responseBody.length);
            httpExchange.getResponseBody().write(responseBody);
        }
    }
}
