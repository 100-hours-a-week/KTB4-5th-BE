package com.dameokja.backend.analysis.presentation;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.support.AnalysisContractTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisRegistrationAtomicityTest extends AnalysisContractTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"analysis_feedbacks", "ingredients"})
    void preservesTheAnalysisAndRetriesRegistrationWithoutPartialWritesWhenStorageFails(String storage) throws Exception {
        Fixture owner = ownerFixture("원자성계약");
        String analysisId = completedAnalysis(owner.userId());
        when(ai.ready()).thenReturn(false);
        try (AutoCloseable rejectedStorage = rejectRegistrationStorage(storage)) {
            registerInventory(owner.userId(), owner.refrigeratorId(), analysisId)
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("GLOBAL-500-001"));
            queryInventory(owner.userId(), owner.refrigeratorId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ingredients").isEmpty());
            assertNoFeedbackQueued();
            queryAnalysis(owner.userId(), analysisId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.results[0].items[0].name").value("우유"));
        }

        registerInventory(owner.userId(), owner.refrigeratorId(), analysisId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdCount").value(1)).andExpect(jsonPath("$.data.mergedCount").value(0));
        queryInventory(owner.userId(), owner.refrigeratorId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients.length()").value(1))
                .andExpect(jsonPath("$.data.ingredients[0].name").value("우유"))
                .andExpect(jsonPath("$.data.ingredients[0].quantity").value(2));
        queryAnalysis(owner.userId(), analysisId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE-404-001"));
        assertFeedbackQueued(owner.refrigeratorId());
    }

    private String completedAnalysis(Long userId) throws Exception {
        String accepted = submitImage(userId, "PRODUCT").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String analysisId = mapper.readTree(accepted).path("data").path("analysisId").asString();
        await().atMost(WAIT_LIMIT).untilAsserted(() -> queryAnalysis(userId, analysisId)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING")));
        completeAnalysis(AI_ANALYSIS_ID, "PRODUCT").andExpect(status().isOk());
        return analysisId;
    }
}
