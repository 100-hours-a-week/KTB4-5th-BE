package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AnalysisJobQueryServiceTest {
    private static final Instant INSTANT = Instant.parse("2026-10-08T01:00:00Z");
    private static final String RESULT = """
            {"documentType":{"value":"RECEIPT","confidence":0.9},"imageQuality":{"score":0.8,"issues":[]},"items":[],
             "modelTrace":{"pipelineVersion":"v1","ocrVersion":"v1","llmModel":"test","normalizerVersion":"v1","policyVersion":"v1"}}
            """;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Clock clock = mock(Clock.class);
    private final AnalysisJobStore analysisJobStore = new AnalysisJobStore(clock, Duration.ofHours(1));
    private MockRestServiceServer mockRestServiceServer;
    private AnalysisJobQueryService analysisJobQueryService;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(INSTANT);
        when(clock.withZone(any())).thenReturn(Clock.fixed(INSTANT, BusinessTime.ZONE));
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://ai.example.com").defaultHeader("X-Internal-API-Key", "test-key");
        mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        analysisJobQueryService = new AnalysisJobQueryService(analysisJobStore, new AiAnalysisClient(restClientBuilder.build()));
        analysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32))));
        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-first");
        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getLast().requestId(), "ai-second");
    }

    @AfterEach
    void verifyAiRequests() { mockRestServiceServer.verify(); }

    @ParameterizedTest
    @CsvSource({"QUEUED,QUEUED,QUEUED", "PROCESSING,QUEUED,PROCESSING", "COMPLETED,QUEUED,PROCESSING", "FAILED,PROCESSING,PROCESSING",
            "COMPLETED,COMPLETED,COMPLETED", "COMPLETED,FAILED,PARTIALLY_COMPLETED", "FAILED,FAILED,FAILED", "FAILED,QUEUED,PROCESSING"})
    void aggregatesStatusesAndPreservesImageOrder(AnalysisStatus firstAnalysisStatus, AnalysisStatus secondAnalysisStatus, AnalysisStatus analysisStatus) {
        expectView("ai-first", firstAnalysisStatus.name());
        expectView("ai-second", secondAnalysisStatus.name());
        AnalysisJobResult analysisJobResult = analysisJobQueryService.get(7L, analysisJob.id());
        assertThat(analysisJobResult.status()).isEqualTo(analysisStatus);
        assertThat(analysisJobResult.analysisJob()).isEqualTo(analysisJobStore.findOwned(analysisJob.id(), 7L));
        assertThat(analysisJobResult.analysisImageResults()).extracting(AnalysisImageResult::imageObjectKey).containsExactly("first", "second");
        assertThat(analysisJobResult.analysisImageResults()).extracting(AnalysisImageResult::status).containsExactly(firstAnalysisStatus, secondAnalysisStatus);
        AnalysisImageResult analysisImageResult = analysisJobResult.analysisImageResults().getFirst();
        if (firstAnalysisStatus == AnalysisStatus.COMPLETED) {
            assertThat(analysisImageResult.recognitionStatus()).isEqualTo(RecognitionStatus.UNRECOGNIZED);
            assertThat(analysisImageResult.documentType()).isEqualTo(new AnalysisImageResult.DocumentType("RECEIPT", 0.9));
            assertThat(analysisImageResult.imageQuality()).isEqualTo(new AnalysisImageResult.ImageQuality(0.8, List.of()));
        } else {
            assertThat(analysisImageResult.recognitionStatus()).isNull();
            assertThat(analysisImageResult.items()).isEmpty();
        }
        if (firstAnalysisStatus == AnalysisStatus.FAILED) {
            assertThat(analysisImageResult.error()).isEqualTo(new AnalysisImageResult.Error("MODEL_UNAVAILABLE", "분석 실패", true, 30000));
        } else {
            assertThat(analysisImageResult.error()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"OWNER,GLOBAL-403-001", "MISSING,IMAGE-404-001", "EXPIRED,IMAGE-404-001"})
    void rejectsUnavailableJobsBeforeCallingAi(String scenario, String code) {
        if (scenario.equals("EXPIRED")) {
            when(clock.instant()).thenReturn(INSTANT.plusSeconds(3600));
        }
        String analysisId = analysisJob.id();
        if (scenario.equals("MISSING")) {
            analysisId = "missing";
        }
        Long userId = 7L;
        if (scenario.equals("OWNER")) {
            userId = 8L;
        }
        Long lookupUserId = userId;
        String lookupAnalysisId = analysisId;
        assertThatThrownBy(() -> analysisJobQueryService.get(lookupUserId, lookupAnalysisId)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"404,IMAGE-503-002", "429,IMAGE-429-002", "500,IMAGE-503-002", "503,IMAGE-503-002"})
    void mapsLookupErrorsAndLeavesStoredRegistrationsIntact(int upstreamStatus, String code) {
        mockRestServiceServer.expect(requestTo("http://ai.example.com/ai/v1/analyses/ai-first"))
                .andRespond(withStatus(HttpStatusCode.valueOf(upstreamStatus)));
        assertThatThrownBy(() -> analysisJobQueryService.get(7L, analysisJob.id())).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo(code);
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsExactly("ai-first", "ai-second");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"analysisId\":\"ai-first\"}", "{\"analysisId\":\"wrong-id\",\"status\":\"QUEUED\"}",
            "{\"analysisId\":\"ai-first\",\"status\":\"UNKNOWN\"}", "{\"analysisId\":\"ai-first\",\"status\":\"COMPLETED\"}",
            "{\"analysisId\":\"ai-first\",\"status\":\"COMPLETED\",\"result\":{}}", "{\"analysisId\":\"ai-first\",\"status\":\"FAILED\"}",
            "{\"analysisId\":\"ai-first\",\"status\":\"PARTIALLY_COMPLETED\"}"})
    void rejectsInvalidAiResponses(String body) {
        mockRestServiceServer.expect(requestTo("http://ai.example.com/ai/v1/analyses/ai-first")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> analysisJobQueryService.get(7L, analysisJob.id())).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("IMAGE-503-002");
    }

    @ParameterizedTest
    @CsvSource({"RECOGNIZED,RECOGNIZED,RECOGNIZED", "UNRECOGNIZED,RECOGNIZED,UNRECOGNIZED", "NEEDS_REVIEW,AI_ESTIMATED,NEEDS_REVIEW",
            "AI_ESTIMATED,RECOGNIZED,AI_ESTIMATED", "RECOGNIZED,UNRECOGNIZED,UNRECOGNIZED", "AI_ESTIMATED,NEEDS_REVIEW,NEEDS_REVIEW"})
    void selectsLowestRecognitionStatusAndConvertsItemFields(String firstDisplayStatus, String secondDisplayStatus, RecognitionStatus recognitionStatus) {
        expectView("ai-first", "COMPLETED", createItem(firstDisplayStatus), createItem(secondDisplayStatus));
        expectView("ai-second", "QUEUED");
        AnalysisImageResult analysisImageResult = analysisJobQueryService.get(7L, analysisJob.id()).analysisImageResults().getFirst();
        assertThat(analysisImageResult.recognitionStatus()).isEqualTo(recognitionStatus);
        assertThat(analysisImageResult.items()).extracting(AnalysisImageResult.Item::displayStatus)
                .containsExactly(RecognitionStatus.valueOf(firstDisplayStatus), RecognitionStatus.valueOf(secondDisplayStatus));
        AnalysisImageResult.Item item = analysisImageResult.items().getFirst();
        assertThat(item.name()).isEqualTo(new AnalysisImageResult.Name("우유", 0.9));
        assertThat(item.category()).isEqualTo(new AnalysisImageResult.Category("DAIRY", 0.8));
        assertThat(item.storageType()).isEqualTo("REFRIGERATED");
        assertThat(item.quantity()).isEqualTo(new AnalysisImageResult.Quantity(2, 0.7));
        assertThat(item.weight()).isEqualTo(new AnalysisImageResult.Weight(null, "NONE", 0.0));
        assertThat(item.expiration()).isEqualTo(new AnalysisImageResult.Expiration(LocalDate.parse("2026-10-12"), 0.6));
        assertThat(item.reviewReasons()).containsExactly("DATE_REVIEW");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", ""})
    void rejectsInvalidItemDisplayStatus(String displayStatus) {
        expectView("ai-first", "COMPLETED", createItem(displayStatus));
        assertThatThrownBy(() -> analysisJobQueryService.get(7L, analysisJob.id())).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("IMAGE-503-002");
    }

    @Test
    void returnsQueuedForImagesWithoutAiRegistration() {
        AnalysisJob pendingAnalysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("pending", "ab".repeat(32))));
        AnalysisJobResult analysisJobResult = analysisJobQueryService.get(7L, pendingAnalysisJob.id());
        assertThat(analysisJobResult.status()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(analysisJobResult.analysisImageResults().getFirst()).isEqualTo(new AnalysisImageResult("pending", AnalysisStatus.QUEUED, null, null, null, List.of(), null));
    }

    @Test
    void queriesAiAgainOnLaterPoll() {
        expectView("ai-first", "QUEUED");
        expectView("ai-second", "QUEUED");
        expectView("ai-first", "COMPLETED");
        expectView("ai-second", "COMPLETED");
        assertThat(analysisJobQueryService.get(7L, analysisJob.id()).status()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(analysisJobQueryService.get(7L, analysisJob.id()).status()).isEqualTo(AnalysisStatus.COMPLETED);
    }

    @Test
    void preservesWeightAndEscapedItemNameWithMissingQuantity() {
        ObjectNode objectNode = createItem("RECOGNIZED").put("measureType", "WEIGHT").putNull("quantity");
        objectNode.putObject("weight").put("value", 1750.5).put("unit", "G").put("source", "OCR").put("confidence", 0.7);
        ((ObjectNode) objectNode.get("name")).put("value", "우유 \"특가\"\\냉장\n");
        expectView("ai-first", "COMPLETED", objectNode);
        expectView("ai-second", "QUEUED");
        AnalysisImageResult.Item item = analysisJobQueryService.get(7L, analysisJob.id()).analysisImageResults().getFirst().items().getFirst();
        assertThat(item.quantity()).isNull();
        assertThat(item.weight()).isEqualTo(new AnalysisImageResult.Weight(new BigDecimal("1750.5"), "G", 0.7));
        assertThat(item.name().value()).isEqualTo("우유 \"특가\"\\냉장\n");
    }

    private ObjectNode createItem(String displayStatus) {
        return ((ObjectNode) jsonMapper.readTree("""
                {"itemId":"item-1","displayStatus":"RECOGNIZED","name":{"value":"우유","normalizedValue":"우유","source":"OCR","confidence":0.9},
                 "category":{"value":"DAIRY","source":"MODEL_INFERENCE","confidence":0.8},"storageType":"REFRIGERATED","measureType":"COUNT",
                 "quantity":{"value":2,"source":"OCR","confidence":0.7},"weight":{"value":null,"unit":"NONE","source":"BUSINESS_RULE","confidence":0.0},
                 "expiration":{"date":"2026-10-12","dateType":"USE_BY","source":"OCR","confidence":0.6},
                 "reviewReasons":["DATE_REVIEW"]}
                """)).put("displayStatus", displayStatus);
    }

    private void expectView(String analysisId, String status, ObjectNode... objectNodes) {
        ObjectNode objectNode = jsonMapper.createObjectNode().put("analysisId", analysisId).put("status", status)
                .put("submittedAt", INSTANT.toString()).putNull("result").putNull("error");
        if (status.equals("COMPLETED")) {
            ObjectNode resultObjectNode = (ObjectNode) jsonMapper.readTree(RESULT);
            resultObjectNode.set("items", jsonMapper.valueToTree(List.of(objectNodes)));
            objectNode.set("result", resultObjectNode);
        }
        if (status.equals("FAILED")) {
            objectNode.putObject("error").put("code", "MODEL_UNAVAILABLE").put("message", "분석 실패").put("retryable", true).put("retryAfterMs", 30000);
        }
        mockRestServiceServer.expect(requestTo("http://ai.example.com/ai/v1/analyses/" + analysisId)).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-API-Key", "test-key")).andRespond(withSuccess(jsonMapper.writeValueAsString(objectNode), MediaType.APPLICATION_JSON));
    }
}
