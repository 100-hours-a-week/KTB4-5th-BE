package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.support.AnalysisDatabaseContractTestSupport;
import com.dameokja.backend.analysis.support.AnalysisDatabaseJobFixture;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = "analysis.result-ttl=1h")
class AnalysisJobQueryWebTest extends AnalysisDatabaseContractTestSupport {
    private static final Instant INSTANT = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtProvider jwtProvider;
    @Autowired AnalysisDatabaseJobFixture analysisDatabaseJobFixture;
    @Autowired ObjectMapper objectMapper;
    private MockMvc mockMvc;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setup() {
        for (Long userId : List.of(7L, 8L, 9L)) {
            analysisDatabaseJobFixture.ensureUser(userId);
        }
        clock.set(INSTANT.toString());
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        analysisJob = analysisDatabaseJobFixture.create(7L, "AUTO",
                List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32))));
        analysisDatabaseJobFixture.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-first");
        analysisDatabaseJobFixture.register(analysisJob.id(), 7L, analysisJob.analysisImages().getLast().requestId(), "ai-second");
    }

    @ParameterizedTest
    @CsvSource({"PROCESSING,PROCESSING,PROCESSING,", "COMPLETED,PROCESSING,PROCESSING,UNRECOGNIZED", "FAILED,PROCESSING,PROCESSING,",
            "COMPLETED,COMPLETED,COMPLETED,UNRECOGNIZED", "COMPLETED,FAILED,PARTIALLY_COMPLETED,UNRECOGNIZED", "FAILED,FAILED,FAILED,"})
    void aggregatesStatusesAndPreservesImageOrder(String firstStatus, String secondStatus, String groupStatus, String recognitionStatus) throws Exception {
        recordCompletion("ai-first", firstStatus);
        recordCompletion("ai-second", secondStatus);
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
    void rejectsUnavailableJobs(String scenario, int status, String code) throws Exception {
        if (scenario.equals("EXPIRED")) {
            AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, INSTANT.atZone(BusinessTime.ZONE).toOffsetDateTime(),
                    new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of()), null);
            analysisDatabaseJobFixture.complete("ai-first", completion);
            analysisDatabaseJobFixture.complete("ai-second", completion);
            clock.set(INSTANT.plusSeconds(3600).toString());
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
        recordCompletion("ai-first", "COMPLETED", createItem(firstDisplayStatus), createItem(secondDisplayStatus));
        recordCompletion("ai-second", "FAILED");
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
        recordCompletion("ai-first", "COMPLETED", item);
        recordCompletion("ai-second", "QUEUED");
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.results[0].items[0].reviewReasons").isArray())
                .andExpect(jsonPath("$.data.results[0].items[0].reviewReasons").isEmpty());
    }

    @Test
    void readsNewResultsOnLaterPoll() throws Exception {
        recordCompletion("ai-first", "QUEUED");
        recordCompletion("ai-second", "QUEUED");
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        recordCompletion("ai-first", "COMPLETED");
        recordCompletion("ai-second", "COMPLETED");
        mockMvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisJob.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void preservesWeightAndNullableExpirationFields() throws Exception {
        ObjectNode objectNode = createItem("NEEDS_REVIEW").put("measureType", "WEIGHT").putNull("quantity");
        objectNode.putObject("weight").put("value", new BigDecimal("1000.5")).put("unit", "ML");
        objectNode.putObject("expiration").putNull("date").put("dateType", "UNKNOWN");
        recordCompletion("ai-first", "COMPLETED", objectNode);
        recordCompletion("ai-second", "FAILED");
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
        recordCompletion("ai-first", "COMPLETED", item);
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
        AnalysisCompletionItem stored = analysisDatabaseJobFixture.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion().result().items().getFirst();
        assertThat(stored.expiration().estimatedRange().from()).isEqualTo(java.time.LocalDate.parse("2026-10-15"));
        assertThat(stored.expiration().estimatedRange().to()).isEqualTo(java.time.LocalDate.parse("2026-10-18"));
    }

    private ObjectNode createItem(String displayStatus) {
        ObjectNode objectNode = (ObjectNode) objectMapper.readTree("""
                {"itemId":"item-1","name":"우유",
                 "category":"DAIRY","storageType":"REFRIGERATED","measureType":"COUNT",
                 "quantity":2,"weight":{"value":null,"unit":"NONE"},
                 "expiration":{"date":"2026-10-12","dateType":"USE_BY","estimatedRange":null},
                 "reviewReasons":[{"field":"EXPIRATION","code":"EXPIRATION_ESTIMATED",
                   "messages":["보관 조건으로 예상했습니다.","소비기한을 확인해 주세요."]}]}
                """);
        return objectNode.put("displayStatus", displayStatus);
    }

    private Cookie accessToken(Long userId) { return new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)); }
    private void recordCompletion(String analysisId, String status, ObjectNode... itemNodes) {
        AnalysisStatus analysisStatus = AnalysisStatus.valueOf(status);
        if (analysisStatus.isInProgress()) { return; }
        AnalysisCompletionSuccess result = analysisStatus == AnalysisStatus.COMPLETED
                ? new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of(itemNodes).stream()
                        .map(itemNode -> objectMapper.treeToValue(itemNode, AnalysisCompletionItem.class)).toList()) : null;
        AnalysisCompletionFailure error = analysisStatus == AnalysisStatus.FAILED
                ? new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, 30000) : null;
        analysisDatabaseJobFixture.complete(analysisId,
                new AnalysisCompletion(analysisStatus, INSTANT.atZone(BusinessTime.ZONE).toOffsetDateTime(), result, error));
    }

}
