package com.dameokja.backend.analysis.infrastructure.ai;

import com.dameokja.backend.global.config.ai.AiClientConfig;
import com.dameokja.backend.global.config.ai.AiClientProperties;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAnalysisClientTest {
    private static final String URL = "http://ai.example.com";
    private static final String SHA256 = "a".repeat(64);
    private MockRestServiceServer server;
    private AiAnalysisClient client;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        AiClientProperties properties = new AiClientProperties(URI.create(URL), "test-key", Duration.ofSeconds(1), Duration.ofSeconds(2));
        AiClientConfig config = new AiClientConfig();
        httpClient = config.aiHttpClient(properties);
        RestClient.Builder builder = config.restClientBuilder(properties, httpClient, properties.readTimeout());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiAnalysisClient(builder.build());
    }

    @AfterEach
    void closeClient() {
        httpClient.close();
    }

    @Test
    void submitsImageUsingAiContractAndReturnsAcceptance() {
        server.expect(requestTo(URL + "/ai/v1/analyses")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-API-Key", "test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"requestId":"request-1","image":{"objectKey":"uploads/analysis/7/image.png","sha256":"%s"},
                         "inputHint":"AUTO","locale":"ko-KR","timezone":"Asia/Seoul"}
                        """.formatted(SHA256)))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"analysisId":"analysis-1","status":"QUEUED","submittedAt":"2026-10-07T01:00:00Z","pollAfterMs":1000}
                        """));
        AiImageAnalysisSubmitResponse aiImageAnalysisSubmitResponse = client.submit(new AiImageAnalysisSubmitRequest("request-1", "uploads/analysis/7/image.png", SHA256));
        assertThat(aiImageAnalysisSubmitResponse).extracting(AiImageAnalysisSubmitResponse::analysisId).isEqualTo("analysis-1");
        server.verify();
    }

    @Test
    void looksUpRequestedAnalysisAndReturnsResponse() {
        server.expect(requestTo(URL + "/ai/v1/analyses/analysis-2")).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-API-Key", "test-key")).andRespond(withSuccess("""
                        {"analysisId":"analysis-2","status":"QUEUED","submittedAt":"2026-10-07T01:00:00Z"}
                        """, MediaType.APPLICATION_JSON));
        AiImageAnalysisResponse aiImageAnalysisResponse = client.get("analysis-2");
        assertThat(aiImageAnalysisResponse).extracting(AiImageAnalysisResponse::status).isEqualTo("QUEUED");
        server.verify();
    }

    @Test
    void rejectsEmptySubmissionResponse() {
        server.expect(requestTo(URL + "/ai/v1/analyses")).andRespond(withSuccess());
        assertThatThrownBy(() -> client.submit(new AiImageAnalysisSubmitRequest("request-1", "uploads/analysis/7/image.png", SHA256)))
                .isInstanceOf(RestClientException.class);
        server.verify();
    }

    @Test
    void rejectsEmptyLookupResponse() {
        server.expect(requestTo(URL + "/ai/v1/analyses/analysis-1")).andRespond(withSuccess());
        assertThatThrownBy(() -> client.get("analysis-1")).isInstanceOf(RestClientException.class);
        server.verify();
    }

    @Test
    void sendsUserConfirmedItemAsFeedbackToImageAnalysis() {
        server.expect(requestTo(URL + "/ai/v1/analyses/analysis-3/feedback")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-API-Key", "test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"refrigeratorId":17,"consentForModelImprovement":true,"items":[{"itemId":"item_001","action":"CORRECTED",
                         "confirmed":{"name":"우유","category":"DAIRY","storageType":"REFRIGERATED","measureType":"WEIGHT","quantity":null,
                          "weightValue":1000,"weightUnit":"ML","expirationDate":"2026-10-20","expirationSource":"USER_CONFIRMED"}}]}
                        """, true))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"feedbackId":"feedback-1","analysisId":"analysis-3","status":"RECORDED"}
                        """));
        AiAnalysisFeedbackResponse aiAnalysisFeedbackResponse = client.feedback("analysis-3", milkFeedback());
        assertThat(aiAnalysisFeedbackResponse).extracting(AiAnalysisFeedbackResponse::status).isEqualTo("RECORDED");
        server.verify();
    }

    @Test
    void rejectsEmptyFeedbackResponse() {
        server.expect(requestTo(URL + "/ai/v1/analyses/analysis-3/feedback")).andRespond(withStatus(HttpStatus.CREATED));
        assertThatThrownBy(() -> client.feedback("analysis-3", milkFeedback())).isInstanceOf(RestClientException.class);
        server.verify();
    }

    private AiAnalysisFeedbackRequest milkFeedback() {
        return new AiAnalysisFeedbackRequest(17L, true, List.of(AiAnalysisFeedbackRequest.Item.corrected("item_001", AiAnalysisFeedbackRequest.Confirmed.userConfirmed("우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.WEIGHT, null, new BigDecimal("1000"), WeightUnit.ML, LocalDate.of(2026, 10, 20)))));
    }
}
