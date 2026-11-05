package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.support.AnalysisContractTestSupport;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisContractIntegrationTest extends AnalysisContractTestSupport {
    @Test
    void observesAnAutomaticPollBeforeAllowingSharedStateCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ai.ready()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            return false;
        });
        try {
            Long userId = ownerFixture("비동기정리경계").userId();
            submitImage(userId, "PRODUCT").andExpect(status().isAccepted());
            assertThat(entered.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> dispatchExecution.awaitIdle(Duration.ZERO)).hasCauseInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
        }
        dispatchExecution.awaitIdle(WAIT_LIMIT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIPT", "PRODUCT"})
    void acceptsAnImageAndReturnsTheCompletedRecognitionThroughTheApi(String documentType) throws Exception {
        Long userId = ownerFixture("분석계약").userId();
        String accepted = submitImage(userId, documentType)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String analysisId = mapper.readTree(accepted).path("data").path("analysisId").asString();
        await().atMost(WAIT_LIMIT).untilAsserted(() -> queryAnalysis(userId, analysisId)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING")));

        completeAnalysis(AI_ANALYSIS_ID, documentType).andExpect(status().isOk());

        queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysisId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].imageObjectKey").value(OBJECT_KEY))
                .andExpect(jsonPath("$.data.results[0].documentType").value(documentType))
                .andExpect(jsonPath("$.data.results[0].items[0].name").value("우유"))
                .andExpect(jsonPath("$.data.results[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.results[0].items[0].expiration.date").value("2026-09-20"));
    }
}
