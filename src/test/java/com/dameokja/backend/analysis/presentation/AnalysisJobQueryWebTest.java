package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.AnalysisJobQueryService;
import com.dameokja.backend.analysis.application.AnalysisJobSubmitService;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringJUnitConfig(AnalysisJobQueryWebTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {"jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d", "analysis.job-ttl=1h"})
class AnalysisJobQueryWebTest {
    private static final Instant INSTANT = Instant.parse("2026-10-08T01:00:00Z");
    private static final String RESULT = """
            {"documentType":{"value":"RECEIPT","confidence":0.9},"imageQuality":{"score":0.8,"issues":[]},
             "items":[],"modelTrace":{"pipelineVersion":"v1","ocrVersion":"v1","llmModel":"test","normalizerVersion":"v1","policyVersion":"v1"}}
            """;
    private static final String ERROR = """
            {"code":"MODEL_UNAVAILABLE","message":"분석 실패","retryable":true,"retryAfterMs":30000}
            """;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtProvider jwtProvider;
    @Autowired Clock clock;
    @Autowired AnalysisJobStore analysisJobStore;
    @Autowired MockRestServiceServer mockRestServiceServer;
    @Autowired ObjectMapper objectMapper;
    private MockMvc mockMvc;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(INSTANT);
        when(clock.withZone(any())).thenReturn(Clock.fixed(INSTANT, BusinessTime.ZONE));
        mockRestServiceServer.reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        analysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32))));
        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-first");
        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getLast().requestId(), "ai-second");
    }

    @AfterEach
    void verifyAiRequests() { mockRestServiceServer.verify(); }

    @ParameterizedTest
    @CsvSource({"QUEUED,QUEUED,QUEUED,", "PROCESSING,QUEUED,PROCESSING,", "COMPLETED,QUEUED,PROCESSING,UNRECOGNIZED", "FAILED,PROCESSING,PROCESSING,",
            "COMPLETED,COMPLETED,COMPLETED,UNRECOGNIZED", "COMPLETED,FAILED,PARTIALLY_COMPLETED,UNRECOGNIZED", "FAILED,FAILED,FAILED,", "FAILED,QUEUED,PROCESSING,"})
    void aggregatesStatusesAndPreservesImageOrder(String firstStatus, String secondStatus, String groupStatus, String recognitionStatus) throws Exception {
        expectView("ai-first", firstStatus);
        expectView("ai-second", secondStatus);
        String response = mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("IMAGE-200-002"))
                .andExpect(jsonPath("$.data.analysisId").value(analysisJob.id())).andExpect(jsonPath("$.data.status").value(groupStatus))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-10-08T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-08T11:00:00+09:00"))
                .andExpect(jsonPath("$.data.results[0].imageObjectKey").value("first"))
                .andExpect(jsonPath("$.data.results[1].imageObjectKey").value("second"))
                .andExpect(jsonPath("$..imageQuality").isEmpty()).andExpect(jsonPath("$..confidence").isEmpty())
                .andExpect(jsonPath("$.data.results[0].status").value(firstStatus))
                .andExpect(jsonPath("$.data.results[0].recognitionStatus").value(recognitionStatus))
                .andExpect(jsonPath("$.data.results[1].status").value(secondStatus)).andReturn().getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(response).path("data");
        assertThat(jsonNode.path("pollAfterMs").isNull()).isEqualTo(!List.of("QUEUED", "PROCESSING").contains(groupStatus));
        if (!jsonNode.path("pollAfterMs").isNull()) {
            assertThat(jsonNode.path("pollAfterMs").asInt()).isEqualTo(1000);
        }
        assertThat(jsonNode.path("error").isNull()).isTrue();
        if (firstStatus.equals("FAILED")) {
            assertThat(jsonNode.path("results").get(0).path("error").path("code").asString()).isEqualTo("MODEL_UNAVAILABLE");
        }
    }

    @ParameterizedTest
    @CsvSource({"LOGIN,401,GLOBAL-401-001", "OWNER,403,GLOBAL-403-001", "MISSING,404,IMAGE-404-001", "EXPIRED,404,IMAGE-404-001"})
    void rejectsUnavailableJobsBeforeCallingAi(String scenario, int status, String code) throws Exception {
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
        MockHttpServletRequestBuilder mockHttpServletRequestBuilder = get("/api/v1/image-analyses/{analysisId}", analysisId);
        if (!scenario.equals("LOGIN")) {
            mockHttpServletRequestBuilder.cookie(accessToken(userId));
        }
        mockMvc.perform(mockHttpServletRequestBuilder)
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    @ParameterizedTest
    @CsvSource({"401,503,IMAGE-503-002", "403,503,IMAGE-503-002", "404,503,IMAGE-503-002", "429,429,IMAGE-429-002", "500,503,IMAGE-503-002", "503,503,IMAGE-503-002"})
    void mapsUpstreamLookupErrorsWithoutMarkingAnalysisFailed(int upstreamStatus, int status, String code) throws Exception {
        mockRestServiceServer.expect(requestTo("http://ai.example.com/ai/v1/analyses/ai-first"))
                .andRespond(withStatus(HttpStatusCode.valueOf(upstreamStatus)));
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsMalformedAiResponseInsteadOfReturningMisleadingResults() throws Exception {
        mockRestServiceServer.expect(requestTo("http://ai.example.com/ai/v1/analyses/ai-first")).andRespond(withSuccess("{", MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IMAGE-503-002"));
    }

    @ParameterizedTest
    @CsvSource({"RECOGNIZED,RECOGNIZED,RECOGNIZED", "UNRECOGNIZED,RECOGNIZED,UNRECOGNIZED", "NEEDS_REVIEW,AI_ESTIMATED,NEEDS_REVIEW",
            "AI_ESTIMATED,RECOGNIZED,AI_ESTIMATED", "RECOGNIZED,UNRECOGNIZED,UNRECOGNIZED", "AI_ESTIMATED,NEEDS_REVIEW,NEEDS_REVIEW"})
    void aggregatesLowestRecognitionStatusAndPreservesItemFields(String firstDisplayStatus, String secondDisplayStatus, String recognitionStatus) throws Exception {
        expectView("ai-first", "COMPLETED", createItem(firstDisplayStatus), createItem(secondDisplayStatus));
        expectView("ai-second", "FAILED");
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.results[0].recognitionStatus").value(recognitionStatus))
                .andExpect(jsonPath("$.data.results[0].items[0].displayStatus").value(firstDisplayStatus))
                .andExpect(jsonPath("$.data.results[0].documentType").value("RECEIPT"))
                .andExpect(jsonPath("$..imageQuality").isEmpty())
                .andExpect(jsonPath("$..confidence").isEmpty())
                .andExpect(jsonPath("$.data.results[0].items[0].storageType").value("REFRIGERATED"))
                .andExpect(jsonPath("$.data.results[0].items[0].name").value("우유"))
                .andExpect(jsonPath("$.data.results[0].items[0].category").value("DAIRY"))
                .andExpect(jsonPath("$.data.results[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.results[0].items[0].expiration").value("2026-10-12"))
                .andExpect(jsonPath("$.data.results[0].items[0].reviewReasons[0]").value("DATE_REVIEW"))
                .andExpect(jsonPath("$.data.results[0].modelTrace").doesNotExist());
    }

    @Test
    void queriesAiAgainOnLaterPoll() throws Exception {
        expectView("ai-first", "QUEUED");
        expectView("ai-second", "QUEUED");
        expectView("ai-first", "COMPLETED");
        expectView("ai-second", "COMPLETED");
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void preservesWeightAndNullableExpirationFields() throws Exception {
        ObjectNode objectNode = createItem("NEEDS_REVIEW").put("measureType", "WEIGHT").putNull("quantity");
        objectNode.putObject("weight").put("value", new BigDecimal("1000.5")).put("unit", "ML").put("source", "OCR").put("confidence", 0.7);
        objectNode.putObject("expiration").putNull("date").put("dateType", "UNKNOWN").putNull("source").putNull("confidence");
        expectView("ai-first", "COMPLETED", objectNode);
        expectView("ai-second", "FAILED");
        String response = mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.results[0].items[0].weight.value").value(1000.5))
                .andExpect(jsonPath("$.data.results[0].items[0].weight.unit").value("ML")).andReturn().getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(response).path("data").path("results").get(0).path("items").get(0);
        assertThat(jsonNode.has("quantity") && jsonNode.path("quantity").isNull()).isTrue();
        assertThat(jsonNode.has("expiration") && jsonNode.path("expiration").isNull()).isTrue();
    }

    private ObjectNode createItem(String displayStatus) {
        ObjectNode objectNode = (ObjectNode) objectMapper.readTree("""
                {"itemId":"item-1","name":{"value":"우유","normalizedValue":"우유","source":"OCR","confidence":0.9,"evidenceText":"우유"},
                 "category":{"value":"DAIRY","source":"MODEL_INFERENCE","confidence":0.8},"storageType":"REFRIGERATED","measureType":"COUNT",
                 "quantity":{"value":2,"source":"OCR","confidence":0.7},"weight":{"value":null,"unit":"NONE","source":"BUSINESS_RULE","confidence":0.0},
                 "expiration":{"date":"2026-10-12","dateType":"USE_BY","source":"OCR","confidence":0.6},"reviewReasons":["DATE_REVIEW"]}
                """);
        return objectNode.put("displayStatus", displayStatus);
    }

    private Cookie accessToken(Long userId) { return new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)); }
    private void expectView(String analysisId, String status, ObjectNode... objectNodes) {
        ObjectNode objectNode = objectMapper.createObjectNode().put("analysisId", analysisId).put("status", status).put("submittedAt", INSTANT.toString());
        objectNode.putNull("result").putNull("error");
        if (status.equals("COMPLETED")) {
            ObjectNode resultObjectNode = (ObjectNode) objectMapper.readTree(RESULT);
            for (ObjectNode itemObjectNode : objectNodes) {
                resultObjectNode.withArray("items").add(itemObjectNode);
            }
            objectNode.set("result", resultObjectNode);
        }
        if (status.equals("FAILED")) {
            objectNode.set("error", objectMapper.readTree(ERROR));
        }
        mockRestServiceServer.expect(requestTo("http://ai.example.com/ai/v1/analyses/" + analysisId)).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-API-Key", "test-key"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(objectNode), MediaType.APPLICATION_JSON));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class, GlobalExceptionHandler.class,
            AnalysisController.class, AnalysisJobQueryService.class, AnalysisJobStore.class})
    static class WebConfiguration {
        @Bean
        static org.springframework.core.convert.ConversionService conversionService() { return new org.springframework.boot.convert.ApplicationConversionService(); }
        @Bean
        Clock clock() { return mock(Clock.class); }
        @Bean
        ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean
        AnalysisJobSubmitService analysisJobSubmitService() { return mock(AnalysisJobSubmitService.class); }
        @Bean
        RestClient.Builder restClientBuilder() { return RestClient.builder().baseUrl("http://ai.example.com").defaultHeader("X-Internal-API-Key", "test-key"); }
        @Bean
        MockRestServiceServer mockRestServiceServer(RestClient.Builder restClientBuilder) { return MockRestServiceServer.bindTo(restClientBuilder).build(); }
        @Bean
        AiAnalysisClient aiAnalysisClient(RestClient.Builder restClientBuilder, MockRestServiceServer mockRestServiceServer) { return new AiAnalysisClient(restClientBuilder.build()); }
    }
}
