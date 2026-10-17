package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.AnalysisJobDeleteService;
import com.dameokja.backend.analysis.application.AnalysisJobQueryService;
import com.dameokja.backend.analysis.application.AnalysisJobSubmitService;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStores;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.OAuthSecurityConfig;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

@MockitoBean(types = OAuthSecurityConfig.class)
@SpringJUnitConfig(AnalysisJobQueryWebTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {"jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d", "analysis.job-ttl=1h", "analysis.result-ttl=1h"})
@ExtendWith(RedisTestSupport.class)
class AnalysisJobQueryWebTest {
    private static final Instant INSTANT = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
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
    @CsvSource({"PROCESSING,PROCESSING,PROCESSING,", "COMPLETED,PROCESSING,PROCESSING,UNRECOGNIZED", "FAILED,PROCESSING,PROCESSING,",
            "COMPLETED,COMPLETED,COMPLETED,UNRECOGNIZED", "COMPLETED,FAILED,PARTIALLY_COMPLETED,UNRECOGNIZED", "FAILED,FAILED,FAILED,"})
    void aggregatesStatusesAndPreservesImageOrder(String firstStatus, String secondStatus, String groupStatus, String recognitionStatus) throws Exception {
        expectView("ai-first", firstStatus);
        expectView("ai-second", secondStatus);
        String response = mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("IMAGE-200-002"))
                .andExpect(jsonPath("$.data.analysisId").value(analysisJob.id())).andExpect(jsonPath("$.data.status").value(groupStatus))
                // 응답은 초가 0이어도 초를 표기하므로, 초를 생략하는 toString 대신 같은 ISO 형식으로 비교한다.
                .andExpect(jsonPath("$.data.submittedAt").value(INSTANT.atZone(BusinessTime.ZONE).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
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
            AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, INSTANT.atZone(BusinessTime.ZONE).toOffsetDateTime(),
                    new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of()), null);
            AnalysisJobStores.complete(analysisJobStore, "ai-first", completion);
            AnalysisJobStores.complete(analysisJobStore, "ai-second", completion);
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
                .andExpect(jsonPath("$.data.results[0].items[0].measureType").value("COUNT"))
                .andExpect(jsonPath("$.data.results[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.results[0].items[0].expiration.date").value("2026-10-12"))
                .andExpect(jsonPath("$.data.results[0].items[0].expiration.dateType").value("USE_BY"))
                .andExpect(jsonPath("$.data.results[0].items[0].expiration.estimatedRange").isEmpty())
                .andExpect(jsonPath("$.data.results[0].items[0].reviewReasons[0].field").value("EXPIRATION"))
                .andExpect(jsonPath("$.data.results[0].items[0].reviewReasons[0].code").value("EXPIRATION_ESTIMATED"))
                .andExpect(jsonPath("$.data.results[0].items[0].reviewReasons[0].messages[0]").value("보관 조건으로 예상했습니다."))
                .andExpect(jsonPath("$.data.results[0].items[0].reviewReasons[0].messages[1]").value("소비기한을 확인해 주세요."))
                .andExpect(jsonPath("$.data.results[0].items[1].reviewReasons[0].field").value("EXPIRATION"))
                .andExpect(jsonPath("$.data.results[0].modelTrace").doesNotExist());
    }

    @Test
    void returnsEmptyReviewReasonsWhenNoReviewIsNeeded() throws Exception {
        ObjectNode item = createItem("RECOGNIZED");
        item.putArray("reviewReasons");
        expectView("ai-first", "COMPLETED", item);
        expectView("ai-second", "QUEUED");
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.results[0].items[0].reviewReasons").isArray())
                .andExpect(jsonPath("$.data.results[0].items[0].reviewReasons").isEmpty());
    }

    @Test
    void readsNewCallbacksOnLaterPollWithoutCallingAi() throws Exception {
        expectView("ai-first", "QUEUED");
        expectView("ai-second", "QUEUED");
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        expectView("ai-first", "COMPLETED");
        expectView("ai-second", "COMPLETED");
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
        assertThat(jsonNode.path("expiration").path("date").isNull()).isTrue();
        assertThat(jsonNode.path("expiration").path("dateType").asText()).isEqualTo("UNKNOWN");
        assertThat(jsonNode.path("expiration").path("estimatedRange").isNull()).isTrue();
    }

    @Test
    void returnsEstimatedRangeWithNullableDateAndItemFields() throws Exception {
        ObjectNode item = createItem("AI_ESTIMATED").putNull("name").putNull("category").putNull("quantity").putNull("storageType").putNull("measureType");
        ((ObjectNode) item.get("weight")).putNull("unit");
        ObjectNode expiration = item.putObject("expiration").putNull("date").put("dateType", "ESTIMATED_CONSUMPTION_WINDOW");
        expiration.putObject("estimatedRange").put("from", "2026-10-15").put("to", "2026-10-18");
        expectView("ai-first", "COMPLETED", item);
        String response = mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andReturn().getResponse().getContentAsString();
        JsonNode result = objectMapper.readTree(response).path("data");
        JsonNode returnedItem = result.path("results").get(0).path("items").get(0);
        for (String field : List.of("name", "category", "storageType", "measureType", "quantity")) {
            assertThat(returnedItem.has(field) && returnedItem.path(field).isNull()).as(field).isTrue();
        }
        assertThat(result.path("expiresAt").isNull()).isTrue();
        JsonNode returnedExpiration = returnedItem.path("expiration");
        assertThat(returnedExpiration.path("date").isNull()).isTrue();
        assertThat(returnedExpiration.path("dateType").asText()).isEqualTo("ESTIMATED_CONSUMPTION_WINDOW");
        assertThat(returnedExpiration.path("estimatedRange").path("from").asText()).isEqualTo("2026-10-15");
        assertThat(returnedExpiration.path("estimatedRange").path("to").asText()).isEqualTo("2026-10-18");
        assertThat(returnedItem.path("weight").path("value").isNull()).isTrue();
        assertThat(returnedItem.path("weight").path("unit").isNull()).isTrue();
        AnalysisCompletionItem stored = analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion().result().items().getFirst();
        assertThat(stored.expiration().estimatedRange().from()).isEqualTo(java.time.LocalDate.parse("2026-10-15"));
        assertThat(stored.expiration().estimatedRange().to()).isEqualTo(java.time.LocalDate.parse("2026-10-18"));
    }

    private ObjectNode createItem(String displayStatus) {
        ObjectNode objectNode = (ObjectNode) objectMapper.readTree("""
                {"itemId":"item-1","name":{"value":"우유","normalizedValue":"우유","source":"OCR","confidence":0.9,"evidenceText":"우유"},
                 "category":{"value":"DAIRY","source":"MODEL_INFERENCE","confidence":0.8},"storageType":"REFRIGERATED","measureType":"COUNT",
                 "quantity":{"value":2,"source":"OCR","confidence":0.7},"weight":{"value":null,"unit":"NONE","source":"BUSINESS_RULE","confidence":0.0},
                 "expiration":{"date":"2026-10-12","dateType":"USE_BY","source":"OCR","confidence":0.6},
                 "reviewReasons":[{"field":"EXPIRATION","code":"EXPIRATION_ESTIMATED",
                   "messages":["보관 조건으로 예상했습니다.","소비기한을 확인해 주세요."]}]}
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
        com.dameokja.backend.analysis.AnalysisStoredResultFixture.store(analysisJobStore, objectNode, INSTANT);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({RedisTestSupport.Configuration.class, SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class, GlobalExceptionHandler.class,
            AnalysisController.class, AnalysisJobQueryService.class, AnalysisJobStore.class})
    static class WebConfiguration {
        @Bean
        static ConversionService conversionService() { return new ApplicationConversionService(); }
        @Bean
        Clock clock() { return mock(Clock.class); }
        @Bean
        ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean
        AnalysisJobSubmitService analysisJobSubmitService() { return mock(AnalysisJobSubmitService.class); }
        @Bean
        AnalysisJobDeleteService analysisJobDeleteService() { return mock(AnalysisJobDeleteService.class); }
        @Bean
        RestClient.Builder restClientBuilder() { return RestClient.builder().baseUrl("http://ai.example.com").defaultHeader("X-Internal-API-Key", "test-key"); }
        @Bean
        MockRestServiceServer mockRestServiceServer(RestClient.Builder restClientBuilder) { return MockRestServiceServer.bindTo(restClientBuilder).build(); }
        @Bean
        AiAnalysisClient aiAnalysisClient(RestClient.Builder restClientBuilder, MockRestServiceServer mockRestServiceServer) { return new AiAnalysisClient(restClientBuilder.build()); }
    }
}
