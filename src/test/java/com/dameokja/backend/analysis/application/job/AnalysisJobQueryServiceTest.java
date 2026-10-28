package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchFixture;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisExpirationDateType;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisReviewReason;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStores;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.RedisTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(RedisTestSupport.class)
class AnalysisJobQueryServiceTest {
    private static final Instant INSTANT = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private static final String RESULT = """
            {"documentType":{"value":"RECEIPT","confidence":0.9},"imageQuality":{"score":0.8,"issues":[]},"items":[],
             "modelTrace":{"pipelineVersion":"v1","ocrVersion":"v1","llmModel":"test","normalizerVersion":"v1","policyVersion":"v1"}}
            """;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Clock clock = mock(Clock.class);
    private final AnalysisJobStore analysisJobStore = new AnalysisJobStore(clock, Duration.ofHours(1), redisTemplate(), JsonMapper.builder().build());
    private AnalysisJobQueryService analysisJobQueryService;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(INSTANT);
        when(clock.withZone(any())).thenReturn(Clock.fixed(INSTANT, BusinessTime.ZONE));
        analysisJobQueryService = new AnalysisJobQueryService(analysisJobStore, new AnalysisOverdueJobExpirer(analysisJobStore, AnalysisDispatchFixture.PROPERTIES, clock));
        analysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32))));
        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-first");
        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getLast().requestId(), "ai-second");
    }

    @ParameterizedTest
    @CsvSource({"PROCESSING,PROCESSING,PROCESSING", "COMPLETED,PROCESSING,PROCESSING", "FAILED,PROCESSING,PROCESSING",
            "COMPLETED,COMPLETED,COMPLETED", "COMPLETED,FAILED,PARTIALLY_COMPLETED", "FAILED,FAILED,FAILED"})
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
        Long lookupUserId = userId;
        String lookupAnalysisId = analysisId;
        assertThatThrownBy(() -> analysisJobQueryService.get(lookupUserId, lookupAnalysisId)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo(code);
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
        AnalysisJob pendingAnalysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("pending", "ab".repeat(32))));
        AnalysisJobResult analysisJobResult = analysisJobQueryService.get(7L, pendingAnalysisJob.id());
        assertThat(analysisJobResult.status()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(analysisJobResult.analysisImageResults().getFirst()).isEqualTo(new AnalysisImageResult("pending", AnalysisStatus.QUEUED, null, null, List.of(), null));
    }

    @Test
    void reportsProcessingWhenOnlySomeImagesHaveAiRegistrations() {
        AnalysisJob pending = analysisJobStore.create(7L, "AUTO",
                List.of(AnalysisImage.pending("registered", "ef".repeat(32)), AnalysisImage.pending("pending", "12".repeat(32))));
        analysisJobStore.register(pending.id(), 7L, pending.analysisImages().getFirst().requestId(), "ai-third");
        AnalysisJobResult result = analysisJobQueryService.get(7L, pending.id());
        assertThat(result.status()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(result.analysisImageResults()).extracting(AnalysisImageResult::status)
                .containsExactly(AnalysisStatus.PROCESSING, AnalysisStatus.QUEUED);
        assertThat(result.analysisJob().expiresAt()).isNull();
    }

    @Test
    void readsNewCallbacksOnLaterPoll() {
        expectView("ai-first", "QUEUED");
        expectView("ai-second", "QUEUED");
        assertThat(analysisJobQueryService.get(7L, analysisJob.id()).status()).isEqualTo(AnalysisStatus.PROCESSING);
        expectView("ai-first", "COMPLETED");
        expectView("ai-second", "COMPLETED");
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
        assertThat(item.weight()).isEqualTo(new AnalysisImageResult.Weight(new BigDecimal("1750.5"), WeightUnit.G));
        assertThat(item.name()).isEqualTo("우유 \"특가\"\\냉장\n");
    }

    private ObjectNode createItem(String displayStatus) {
        return ((ObjectNode) jsonMapper.readTree("""
                {"itemId":"item-1","displayStatus":"RECOGNIZED","name":{"value":"우유","normalizedValue":"우유","source":"OCR","confidence":0.9},
                 "category":{"value":"DAIRY","source":"MODEL_INFERENCE","confidence":0.8},"storageType":"REFRIGERATED","measureType":"COUNT",
                 "quantity":{"value":2,"source":"OCR","confidence":0.7},"weight":{"value":null,"unit":"NONE","source":"BUSINESS_RULE","confidence":0.0},
                 "expiration":{"date":"2026-10-12","dateType":"USE_BY","source":"OCR","confidence":0.6},
                 "reviewReasons":[{"field":"EXPIRATION","code":"EXPIRATION_ESTIMATED","messages":["보관 조건으로 예상했습니다.","소비기한을 확인해 주세요."]},
                   {"field":"QUANTITY","code":"QUANTITY_DEFAULTED","messages":["수량을 확인해 주세요."]}]}
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
        com.dameokja.backend.analysis.AnalysisStoredResultFixture.store(analysisJobStore, objectNode, INSTANT);
    }
}
