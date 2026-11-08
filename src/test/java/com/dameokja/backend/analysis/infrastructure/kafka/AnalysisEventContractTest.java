package com.dameokja.backend.analysis.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionValidator;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.global.common.EventEnvelope;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AnalysisEventContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String REQUEST_ID = "de59bfd4-fb02-38c3-a0fe-e5eb3bc776ad";
    private static final Instant REQUESTED_AT = Instant.parse("2026-10-29T03:00:00Z");

    @Test
    void requestEnvelopeIncludesTheRequiredRefrigeratorContext() {
        EventEnvelope<AnalysisRequestedPayload> event = EventEnvelope.create(AnalysisRequestedPayload.create(42L, 17L,
                AnalysisImage.pending("analysis/42/receipt-001.jpg", "ab".repeat(32), REQUEST_ID), "AUTO", REQUESTED_AT));
        assertThat(mapper.valueToTree(event).at("/payload/refrigeratorId").asLong()).isEqualTo(17L);
    }
    private static final String COMPLETED = """
            {
              "messageId": "6832f444-9225-4b9c-b112-7d29984ef5ba",
              "payload": {
                "occurredAt": "2026-10-29T03:00:15Z",
                "requestId": "de59bfd4-fb02-38c3-a0fe-e5eb3bc776ad", "userId": 42, "refrigeratorId": 17,
                "analysisId": "ai-analysis-001", "status": "COMPLETED", "completedAt": "2026-10-29T03:00:15Z",
                "result": {
                  "documentType": "RECEIPT",
                  "items": [{
                    "itemId": "item-001", "displayStatus": "RECOGNIZED", "name": "우유", "category": "DAIRY",
                    "storageType": "REFRIGERATED", "measureType": "COUNT", "quantity": 1,
                    "weight": {"value": null, "unit": "NONE"},
                    "expiration": {"date": "2026-11-03", "dateType": "USE_BY", "estimatedRange": null},
                    "reviewReasons": []
                  }]
                },
                "error": null
              }
            }
            """;
    private static final String FAILED = """
            {
              "messageId": "82d2dcc0-f936-4479-b3d1-1a99ea094fe0",
              "payload": {
                "occurredAt": "2026-10-29T03:00:15Z",
                "requestId": "de59bfd4-fb02-38c3-a0fe-e5eb3bc776ad", "userId": 42, "refrigeratorId": 17,
                "analysisId": "ai-analysis-001", "status": "FAILED", "completedAt": "2026-10-29T03:00:15Z",
                "result": null,
                "error": {"code": "ANALYSIS_UNAVAILABLE", "message": "일시적인 분석 장애", "retryable": true, "retryAfterMs": null}
              }
            }
            """;

    @Test
    void createsTheAgreedRequestEnvelopeAndImageInput() {
        EventEnvelope<AnalysisRequestedPayload> event = EventEnvelope.create(AnalysisRequestedPayload.create(42L, 17L,
                AnalysisImage.pending("analysis/42/receipt-001.jpg", "ab".repeat(32), REQUEST_ID), "AUTO", REQUESTED_AT));
        JsonNode expected = mapper.readTree("""
                {
                  "messageId": "%s",
                  "payload": {
                    "occurredAt": "2026-10-29T03:00:00Z",
                    "requestId": "%s", "userId": 42, "refrigeratorId": 17,
                    "image": {"objectKey": "analysis/42/receipt-001.jpg", "sha256": "%s"},
                    "inputHint": "AUTO", "locale": "ko-KR", "timezone": "Asia/Seoul"
                  }
                }
                """.formatted(event.messageId(), REQUEST_ID, "ab".repeat(32)));

        assertThat(event.messageId()).isNotBlank().isNotEqualTo(REQUEST_ID);
        assertThat(mapper.readTree(mapper.writeValueAsString(event))).isEqualTo(expected);
        assertThat(mapper.readValue(mapper.writeValueAsString(event), new TypeReference<EventEnvelope<AnalysisRequestedPayload>>() {})).isEqualTo(event);
    }

    @Test
    void readsAiSuccessWithTheExistingResultContractAndWritesTheSameEnvelope() {
        EventEnvelope<AnalysisCompletedPayload> event = mapper.readValue(COMPLETED, new TypeReference<EventEnvelope<AnalysisCompletedPayload>>() {});
        AnalysisCompletion completion = event.payload().completion();
        new AnalysisCompletionValidator().validate(completion);

        assertThat(event.payload().requestId()).isEqualTo(REQUEST_ID);
        assertThat(event.payload().userId()).isEqualTo(42L);
        assertThat(completion.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(completion.completedAt().toInstant()).isEqualTo(Instant.parse("2026-10-29T03:00:15Z"));
        assertThat(completion.result().items().getFirst().name()).isEqualTo("우유");
        assertThat(mapper.readTree(mapper.writeValueAsString(event))).isEqualTo(mapper.readTree(COMPLETED));
    }

    @Test
    void rejectsNonpositiveRefrigeratorIdsInCompletedEnvelopes() {
        for (String replacement : java.util.List.of("\"refrigeratorId\": 0,", "\"refrigeratorId\": -1,")) {
            String invalid = COMPLETED.replace("\"refrigeratorId\": 17,", replacement);
            assertThatThrownBy(() -> mapper.readValue(invalid, new TypeReference<EventEnvelope<AnalysisCompletedPayload>>() {})).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsCompletedEventsWithoutRefrigeratorContextForCompatibility() {
        for (String replacement : java.util.List.of("", "\"refrigeratorId\": null,")) {
            String legacy = COMPLETED.replace("\"refrigeratorId\": 17,", replacement);
            EventEnvelope<AnalysisCompletedPayload> event = mapper.readValue(legacy, new TypeReference<EventEnvelope<AnalysisCompletedPayload>>() {});
            assertThat(event.payload().refrigeratorId()).isNull();
            assertThat(event.payload().analysisId()).isEqualTo("ai-analysis-001");
        }
    }

    @Test
    void readsAiFailureWithoutLosingNullResultOrRetryInformation() {
        EventEnvelope<AnalysisCompletedPayload> event = mapper.readValue(FAILED, new TypeReference<EventEnvelope<AnalysisCompletedPayload>>() {});
        AnalysisCompletion completion = event.payload().completion();
        new AnalysisCompletionValidator().validate(completion);

        assertThat(completion.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(completion.result()).isNull();
        assertThat(completion.error().code()).isEqualTo("ANALYSIS_UNAVAILABLE");
        assertThat(completion.error().retryable()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(event))).isEqualTo(mapper.readTree(FAILED));
    }

    @Test
    void readsOlderPayloadsWithoutDependingOnTheirEventTypeField() {
        String legacy = COMPLETED.replace("\"occurredAt\":", "\"eventType\":\"AnalysisCompleted\",\"occurredAt\":");
        EventEnvelope<AnalysisCompletedPayload> event = mapper.readValue(legacy, new TypeReference<EventEnvelope<AnalysisCompletedPayload>>() {});
        assertThat(event.payload().analysisId()).isEqualTo("ai-analysis-001");
        assertThat(mapper.valueToTree(event).at("/payload").has("eventType")).isFalse();
    }
}
