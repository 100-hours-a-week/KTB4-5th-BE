package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.support.AnalysisDatabaseContractTestSupport;
import com.dameokja.backend.analysis.support.AnalysisRefrigeratorFixture;
import com.dameokja.backend.user.domain.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AnalysisRefrigeratorQueryWebTest extends AnalysisDatabaseContractTestSupport {
    @Autowired AnalysisJobAcceptance acceptance;
    @Autowired AnalysisJobStateAccess states;

    @Test
    void listsAndReadsOnlyJobsForTheUserAndSelectedRefrigerator() throws Exception {
        Fixture owner = ownerFixture("냉장고별 조회");
        AnalysisJob original = accept(owner.userId());
        switchRefrigerator(owner.userId());
        AnalysisJob selected = accept(owner.userId());
        listAnalyses(owner.userId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyses.length()").value(1))
                .andExpect(jsonPath("$.data.analyses[0].analysisId").value(selected.id()));
        queryAnalysis(owner.userId(), original.id()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE-404-001"));
        queryAnalysis(owner.userId(), selected.id()).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active = 0 WHERE user_id = ?", owner.userId());
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active = 1 WHERE user_id = ? AND refrigerator_id = ?", owner.userId(), owner.refrigeratorId());
        queryAnalysis(owner.userId(), original.id()).andExpect(status().isOk());
        listAnalyses(owner.userId()).andExpect(status().isOk()).andExpect(jsonPath("$.data.analyses[0].analysisId").value(original.id()));
    }

    @Test
    void keepsOtherMembersJobsPrivateEvenWhenSharingTheSelectedRefrigerator() throws Exception {
        Fixture owner = ownerFixture("공유 조회");
        User member = user("다른 공유자");
        join(member, refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow(), true, false);
        AnalysisJob own = accept(owner.userId());
        AnalysisJob other = accept(member.getId());
        listAnalyses(owner.userId()).andExpect(status().isOk()).andExpect(jsonPath("$.data.analyses.length()").value(1))
                .andExpect(jsonPath("$.data.analyses[0].analysisId").value(own.id()));
        queryAnalysis(owner.userId(), other.id()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GLOBAL-403-001"));
    }

    @Test
    void rejectsReadsWithoutASelectedRefrigeratorWithoutChangingTheJob() throws Exception {
        Fixture owner = ownerFixture("미선택 조회");
        AnalysisJob job = accept(owner.userId());
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active = 0 WHERE user_id = ?", owner.userId());
        listAnalyses(owner.userId()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REFRIGERATOR-403-001"));
        queryAnalysis(owner.userId(), job.id()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REFRIGERATOR-403-001"));
        assertThat(states.find(job.id())).contains(job);
    }

    @Test
    void excludesAnotherRefrigeratorsBodyBeforeDeserializingIt() throws Exception {
        Fixture owner = ownerFixture("DB 범위 조회");
        AnalysisJob original = accept(owner.userId());
        switchRefrigerator(owner.userId());
        AnalysisJob selected = accept(owner.userId());
        jdbcTemplate.update("UPDATE analysis_job_states SET job_body = JSON_SET(job_body, '$.analysisImages', 1) WHERE analysis_job_id = ?", original.id());
        listAnalyses(owner.userId()).andExpect(status().isOk()).andExpect(jsonPath("$.data.analyses.length()").value(1))
                .andExpect(jsonPath("$.data.analyses[0].analysisId").value(selected.id()));
        queryAnalysis(owner.userId(), original.id()).andExpect(status().isNotFound());
    }

    @Test
    void excludesLegacyJobsWhoseRefrigeratorCannotBeIdentified() throws Exception {
        Fixture owner = ownerFixture("기존 작업 조회");
        AnalysisJob job = accept(owner.userId());
        jdbcTemplate.update("UPDATE analysis_job_states SET job_body = JSON_REMOVE(job_body, '$.refrigeratorId') WHERE analysis_job_id = ?", job.id());
        listAnalyses(owner.userId()).andExpect(status().isOk()).andExpect(jsonPath("$.data.analyses").isEmpty());
        queryAnalysis(owner.userId(), job.id()).andExpect(status().isNotFound());
    }

    private AnalysisJob accept(Long userId) {
        return acceptance.accept(userId, "AUTO", List.of(AnalysisImage.pending("fridge-query", "ab".repeat(32))));
    }

    private void switchRefrigerator(Long userId) {
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active = 0 WHERE user_id = ?", userId);
        AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, userId);
    }
}
