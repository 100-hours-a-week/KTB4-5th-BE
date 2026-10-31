package com.dameokja.backend.analysis.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AnalysisFeedbackEventContractTest {
    private static final String REQUEST_ID = "de59bfd4-fb02-38c3-a0fe-e5eb3bc776ad";
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-31T03:00:00Z");
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void createsTheCommonEnvelopeWithoutChangingFeedbackActionsOrConfirmedValues() {
        AnalysisFeedbackSubmittedEvent analysisFeedbackSubmittedEvent = AnalysisFeedbackSubmittedEvent.create(42L, REQUEST_ID, "ai-analysis-001", feedbackRequest(), OCCURRED_AT);
        JsonNode expectedEvent = objectMapper.readTree("""
                {
                  "messageId": "%s",
                  "payload": {
                    "eventType": "AnalysisFeedbackSubmitted", "occurredAt": "2026-10-31T03:00:00Z",
                    "requestId": "%s", "userId": 42, "analysisId": "ai-analysis-001",
                    "refrigeratorId": 17, "consentForModelImprovement": false,
                    "items": [
                      {"itemId": "item-001", "action": "CONFIRMED"},
                      {"itemId": "item-002", "action": "CORRECTED", "confirmed": {
                        "name": "우유", "category": "DAIRY", "storageType": "REFRIGERATED", "measureType": "COUNT",
                        "quantity": 2, "weightValue": null, "weightUnit": "NONE", "expirationDate": "2026-11-07", "expirationSource": "USER_CONFIRMED"
                      }},
                      {"action": "ADDED", "confirmed": {
                        "name": "쌀", "category": "GRAINS_NOODLE", "storageType": "REFRIGERATED", "measureType": "WEIGHT",
                        "quantity": null, "weightValue": 500, "weightUnit": "G", "expirationDate": null, "expirationSource": "USER_CONFIRMED"
                      }},
                      {"itemId": "item-003", "action": "DELETED"}
                    ]
                  }
                }
                """.formatted(analysisFeedbackSubmittedEvent.messageId(), REQUEST_ID));
        assertThat(analysisFeedbackSubmittedEvent.messageId()).isNotBlank().isNotEqualTo(REQUEST_ID);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(analysisFeedbackSubmittedEvent))).isEqualTo(expectedEvent);
    }

    @Test
    void preservesTheStoredMessageIdentityAndOriginalRequestWhenReadBackForRetry() {
        AnalysisFeedbackSubmittedEvent analysisFeedbackSubmittedEvent = AnalysisFeedbackSubmittedEvent.create(42L, REQUEST_ID, "ai-analysis-001", feedbackRequest(), OCCURRED_AT);
        String storedEvent = objectMapper.writeValueAsString(analysisFeedbackSubmittedEvent);
        AnalysisFeedbackSubmittedEvent restoredEvent = objectMapper.readValue(storedEvent, AnalysisFeedbackSubmittedEvent.class);
        assertThat(restoredEvent).isEqualTo(analysisFeedbackSubmittedEvent);
        assertThat(restoredEvent.payload().requestId()).isEqualTo(REQUEST_ID);
        assertThat(restoredEvent.payload().userId()).isEqualTo(42L);
    }

    private AiAnalysisFeedbackRequest feedbackRequest() {
        AiAnalysisFeedbackRequest.Confirmed correctedItem = AiAnalysisFeedbackRequest.Confirmed.userConfirmed("우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 2, null, WeightUnit.NONE, LocalDate.parse("2026-11-07"));
        AiAnalysisFeedbackRequest.Confirmed addedItem = AiAnalysisFeedbackRequest.Confirmed.userConfirmed("쌀", IngredientCategory.GRAINS_NOODLE,
                StorageType.REFRIGERATED, MeasureType.WEIGHT, null, new BigDecimal("500"), WeightUnit.G, null);
        return new AiAnalysisFeedbackRequest(17L, false, List.of(new AiAnalysisFeedbackRequest.Item("item-001", "CONFIRMED", null),
                AiAnalysisFeedbackRequest.Item.corrected("item-002", correctedItem), new AiAnalysisFeedbackRequest.Item(null, "ADDED", addedItem),
                new AiAnalysisFeedbackRequest.Item("item-003", "DELETED", null)));
    }
}
