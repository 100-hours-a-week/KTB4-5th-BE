package com.dameokja.backend.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

class AnalysisCompletionJsonCompatibilityTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String STORED_JOB = """
            {
              "id":"batch-1","userId":7,"inputHint":"RECEIPT",
              "analysisImages":[
                {"objectKey":"image-1","sha256":"hash-1","requestId":"request-1","aiAnalysisId":"ai-1",
                 "completion":{"status":"COMPLETED","completedAt":"2026-10-13T10:00:00+09:00",
                   "result":{"documentType":"RECEIPT","items":[
                     {"itemId":"item-1","displayStatus":"NEEDS_REVIEW","name":"사과","category":"FRUIT",
                      "storageType":"REFRIGERATED","measureType":"COUNT","quantity":2,
                      "weight":{"value":null,"unit":"NONE"},
                      "expiration":{"date":null,"dateType":"ESTIMATED_CONSUMPTION_WINDOW",
                        "estimatedRange":{"from":"2026-10-14","to":"2026-10-18"}},
                      "reviewReasons":[{"field":"EXPIRATION","code":"EXPIRATION_ESTIMATED","messages":["예상 기간 확인"]}]}
                   ]},"error":null}},
                {"objectKey":"image-2","sha256":"hash-2","requestId":"request-2","aiAnalysisId":"ai-2",
                 "completion":{"status":"FAILED","completedAt":"2026-10-13T10:01:00+09:00","result":null,
                   "error":{"code":"MODEL_UNAVAILABLE","message":"분석 실패","retryable":true,"retryAfterMs":1000}}}
              ],
              "submittedAt":"2026-10-13T09:00:00+09:00","expiresAt":"2026-10-13T11:01:00+09:00","notificationPending":true
            }
            """;

    @Test
    void readsPreviouslyStoredJobAndWritesTheSameJsonShape() {
        AnalysisJob job = mapper.readerFor(AnalysisJob.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(STORED_JOB);
        AnalysisCompletion completion = job.analysisImages().getFirst().completion();
        AnalysisCompletionItem item = completion.result().items().getFirst();
        assertThat(completion.completedAt()).isEqualTo(OffsetDateTime.parse("2026-10-13T10:00:00+09:00"));
        assertThat(item.expiration().date()).isNull();
        assertThat(item.expiration().estimatedRange().from()).isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(item.expiration().estimatedRange().to()).isEqualTo(LocalDate.parse("2026-10-18"));
        assertThat(item.reviewReasons().getFirst().field()).isEqualTo(AnalysisReviewReason.Field.EXPIRATION);
        assertThat(job.analysisImages().getLast().completion().error().retryable()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(job))).isEqualTo(mapper.readTree(STORED_JOB));
    }

    @Test
    void keepsDeserializedResultAndReviewReasonCollectionsImmutable() {
        AnalysisJob job = mapper.readValue(STORED_JOB, AnalysisJob.class);
        AnalysisCompletionSuccess result = job.analysisImages().getFirst().completion().result();
        assertThatThrownBy(() -> result.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.items().getFirst().reviewReasons().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
