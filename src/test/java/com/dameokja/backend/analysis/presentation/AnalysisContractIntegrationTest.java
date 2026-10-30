package com.dameokja.backend.analysis.presentation;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.support.AnalysisContractTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisContractIntegrationTest extends AnalysisContractTestSupport {
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
