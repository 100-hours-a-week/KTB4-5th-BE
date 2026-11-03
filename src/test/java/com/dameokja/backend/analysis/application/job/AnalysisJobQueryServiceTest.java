package com.dameokja.backend.analysis.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisExpirationDateType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisReviewReason;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.analysis.support.AnalysisDatabaseJobFixture;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.support.MySqlJpaTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Import({com.dameokja.backend.refrigerator.application.RefrigeratorAccessService.class,
        com.dameokja.backend.user.application.access.UserAccessService.class, AnalysisJobQueryService.class, AnalysisDatabaseJobExpirer.class, AnalysisJobStateAccess.class,
        AnalysisDatabaseJobFixture.class, AnalysisJobQueryServiceTest.Config.class})
@TestPropertySource(properties = "analysis.result-ttl=1h")
class AnalysisJobQueryServiceTest extends MySqlJpaTest {
    private static final Instant INSTANT = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    @MockitoBean Clock clock;
    @Autowired AnalysisDatabaseJobFixture analysisDatabaseJobFixture;
    @Autowired AnalysisJobQueryService analysisJobQueryService;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(INSTANT);
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), BusinessTime.ZONE));
        analysisJob = analysisDatabaseJobFixture.create(7L, "AUTO",
                List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32))));
        analysisDatabaseJobFixture.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-first");
        analysisDatabaseJobFixture.register(analysisJob.id(), 7L, analysisJob.analysisImages().getLast().requestId(), "ai-second");
    }

    @ParameterizedTest
    @CsvSource({"PROCESSING,PROCESSING,PROCESSING", "COMPLETED,PROCESSING,PROCESSING", "FAILED,PROCESSING,PROCESSING",
            "COMPLETED,COMPLETED,COMPLETED", "COMPLETED,FAILED,PARTIALLY_COMPLETED", "FAILED,FAILED,FAILED"})
    void aggregatesStatusesAndPreservesImageOrder(AnalysisStatus firstAnalysisStatus, AnalysisStatus secondAnalysisStatus, AnalysisStatus analysisStatus) {
        recordCompletion("ai-first", firstAnalysisStatus.name());
        recordCompletion("ai-second", secondAnalysisStatus.name());
        AnalysisJobResult analysisJobResult = analysisJobQueryService.get(7L, analysisJob.id());
        assertThat(analysisJobResult.status()).isEqualTo(analysisStatus);
        assertThat(analysisJobResult.analysisJob()).isEqualTo(analysisDatabaseJobFixture.findOwned(analysisJob.id(), 7L));
        assertThat(analysisJobResult.analysisImageResults()).extracting(AnalysisImageResult::imageObjectKey).containsExactly("first", "second");
        assertThat(analysisJobResult.analysisImageResults()).extracting(AnalysisImageResult::status).containsExactly(firstAnalysisStatus, secondAnalysisStatus);
        AnalysisImageResult analysisImageResult = analysisJobResult.analysisImageResults().getFirst();
        if (firstAnalysisStatus == AnalysisStatus.COMPLETED) {
            assertThat(analysisImageResult.recognitionStatus()).isEqualTo(RecognitionStatus.UNRECOGNIZED);
            assertThat(analysisImageResult.documentType()).isEqualTo(AnalysisDocumentType.RECEIPT);
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
    void rejectsUnavailableJobs(String scenario, String code) {
        if (scenario.equals("EXPIRED")) {
            AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, INSTANT.atZone(BusinessTime.ZONE).toOffsetDateTime(),
                    new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of()), null);
            analysisDatabaseJobFixture.complete("ai-first", completion);
            analysisDatabaseJobFixture.complete("ai-second", completion);
            when(clock.instant()).thenReturn(INSTANT.plusSeconds(3600));
        }
        String analysisId = analysisJob.id();
        if (scenario.equals("MISSING")) {
            analysisId = "missing";
        }
        Long userId = 7L;
        if (scenario.equals("OWNER")) {
            userId = 8L;
            analysisDatabaseJobFixture.ensureUser(userId);
        }
        Long lookupUserId = userId;
        String lookupAnalysisId = analysisId;
        assertThatThrownBy(() -> analysisJobQueryService.get(lookupUserId, lookupAnalysisId)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"RECOGNIZED,RECOGNIZED,RECOGNIZED", "UNRECOGNIZED,RECOGNIZED,UNRECOGNIZED", "NEEDS_REVIEW,AI_ESTIMATED,NEEDS_REVIEW",
            "AI_ESTIMATED,RECOGNIZED,AI_ESTIMATED", "RECOGNIZED,UNRECOGNIZED,UNRECOGNIZED", "AI_ESTIMATED,NEEDS_REVIEW,NEEDS_REVIEW"})
    void selectsLowestRecognitionStatusAndConvertsItemFields(String firstDisplayStatus, String secondDisplayStatus, RecognitionStatus recognitionStatus) {
        recordCompletion("ai-first", "COMPLETED", createItem(firstDisplayStatus), createItem(secondDisplayStatus));
        recordCompletion("ai-second", "QUEUED");
        AnalysisImageResult analysisImageResult = analysisJobQueryService.get(7L, analysisJob.id()).analysisImageResults().getFirst();
        assertThat(analysisImageResult.recognitionStatus()).isEqualTo(recognitionStatus);
        assertThat(analysisImageResult.items()).extracting(AnalysisImageResult.Item::displayStatus)
                .containsExactly(RecognitionStatus.valueOf(firstDisplayStatus), RecognitionStatus.valueOf(secondDisplayStatus));
        AnalysisImageResult.Item item = analysisImageResult.items().getFirst();
        assertThat(item.name()).isEqualTo("우유");
        assertThat(item.category()).isEqualTo(IngredientCategory.DAIRY);
        assertThat(item.storageType()).isEqualTo(StorageType.REFRIGERATED);
        assertThat(item.measureType()).isEqualTo(MeasureType.COUNT);
        assertThat(item.quantity()).isEqualTo(2);
        assertThat(item.weight()).isEqualTo(new AnalysisImageResult.Weight(null, WeightUnit.NONE));
        assertThat(item.expiration().date()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(item.expiration().dateType()).isEqualTo(AnalysisExpirationDateType.USE_BY);
        assertThat(item.reviewReasons()).containsExactly(
                new AnalysisReviewReason(AnalysisReviewReason.Field.EXPIRATION, AnalysisReviewReason.Code.EXPIRATION_ESTIMATED,
                        List.of("보관 조건으로 예상했습니다.", "소비기한을 확인해 주세요.")),
                new AnalysisReviewReason(AnalysisReviewReason.Field.QUANTITY, AnalysisReviewReason.Code.QUANTITY_DEFAULTED, List.of("수량을 확인해 주세요.")));
    }

    @Test
    void returnsQueuedForImagesWithoutAiRegistration() {
        AnalysisJob pendingAnalysisJob = analysisDatabaseJobFixture.create(7L, "AUTO", List.of(AnalysisImage.pending("pending", "ab".repeat(32))));
        AnalysisJobResult analysisJobResult = analysisJobQueryService.get(7L, pendingAnalysisJob.id());
        assertThat(analysisJobResult.status()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(analysisJobResult.analysisImageResults().getFirst()).isEqualTo(new AnalysisImageResult("pending", AnalysisStatus.QUEUED, null, null, List.of(), null));
    }

    @Test
    void reportsProcessingWhenOnlySomeImagesHaveAiRegistrations() {
        AnalysisJob pending = analysisDatabaseJobFixture.create(7L, "AUTO",
                List.of(AnalysisImage.pending("registered", "ef".repeat(32)), AnalysisImage.pending("pending", "12".repeat(32))));
        analysisDatabaseJobFixture.register(pending.id(), 7L, pending.analysisImages().getFirst().requestId(), "ai-third");
        AnalysisJobResult result = analysisJobQueryService.get(7L, pending.id());
        assertThat(result.status()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(result.analysisImageResults()).extracting(AnalysisImageResult::status)
                .containsExactly(AnalysisStatus.PROCESSING, AnalysisStatus.QUEUED);
        assertThat(result.analysisJob().expiresAt()).isNull();
    }

    @Test
    void readsNewResultsOnLaterPoll() {
        recordCompletion("ai-first", "QUEUED");
        recordCompletion("ai-second", "QUEUED");
        assertThat(analysisJobQueryService.get(7L, analysisJob.id()).status()).isEqualTo(AnalysisStatus.PROCESSING);
        recordCompletion("ai-first", "COMPLETED");
        recordCompletion("ai-second", "COMPLETED");
        assertThat(analysisJobQueryService.get(7L, analysisJob.id()).status()).isEqualTo(AnalysisStatus.COMPLETED);
    }

    @Test
    void preservesWeightAndEscapedItemNameWithMissingQuantity() {
        ObjectNode objectNode = createItem("RECOGNIZED").put("measureType", "WEIGHT").putNull("quantity");
        objectNode.putObject("weight").put("value", 1750.5).put("unit", "G");
        objectNode.put("name", "우유 \"특가\"\\냉장\n");
        recordCompletion("ai-first", "COMPLETED", objectNode);
        recordCompletion("ai-second", "QUEUED");
        AnalysisImageResult.Item item = analysisJobQueryService.get(7L, analysisJob.id()).analysisImageResults().getFirst().items().getFirst();
        assertThat(item.quantity()).isNull();
        assertThat(item.weight()).isEqualTo(new AnalysisImageResult.Weight(new BigDecimal("1750.5"), WeightUnit.G));
        assertThat(item.name()).isEqualTo("우유 \"특가\"\\냉장\n");
    }

    private ObjectNode createItem(String displayStatus) {
        return ((ObjectNode) jsonMapper.readTree("""
                {"itemId":"item-1","displayStatus":"RECOGNIZED","name":"우유",
                 "category":"DAIRY","storageType":"REFRIGERATED","measureType":"COUNT",
                 "quantity":2,"weight":{"value":null,"unit":"NONE"},
                 "expiration":{"date":"2026-10-12","dateType":"USE_BY","estimatedRange":null},
                 "reviewReasons":[{"field":"EXPIRATION","code":"EXPIRATION_ESTIMATED","messages":["보관 조건으로 예상했습니다.","소비기한을 확인해 주세요."]},
                   {"field":"QUANTITY","code":"QUANTITY_DEFAULTED","messages":["수량을 확인해 주세요."]}]}
                """)).put("displayStatus", displayStatus);
    }

    private void recordCompletion(String analysisId, String status, ObjectNode... itemNodes) {
        AnalysisStatus analysisStatus = AnalysisStatus.valueOf(status);
        if (analysisStatus.isInProgress()) { return; }
        AnalysisCompletionSuccess result = analysisStatus == AnalysisStatus.COMPLETED
                ? new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of(itemNodes).stream()
                        .map(itemNode -> jsonMapper.treeToValue(itemNode, AnalysisCompletionItem.class)).toList()) : null;
        AnalysisCompletionFailure error = analysisStatus == AnalysisStatus.FAILED
                ? new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, 30000) : null;
        analysisDatabaseJobFixture.complete(analysisId,
                new AnalysisCompletion(analysisStatus, INSTANT.atZone(BusinessTime.ZONE).toOffsetDateTime(), result, error));
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
    }
}
