package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.application.job.AnalysisDatabaseJobExpirer;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedEvent;
import com.dameokja.backend.analysis.support.AnalysisContractTestSupport;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.ResultActions;

class AnalysisDatabaseJobWebTest extends AnalysisContractTestSupport {
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisDatabaseJobExpirer analysisDatabaseJobExpirer;
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Value("${analysis.job-timeout:PT30M30S}") Duration jobTimeout;
    @Autowired JwtProvider jwtProvider;

    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        jdbcTemplate.update("DELETE FROM analysis_outbox");
        jdbcTemplate.update("DELETE FROM analysis_job_requests");
        jdbcTemplate.update("DELETE FROM analysis_job_states");
        super.cleanDatabaseAndResetTime();
    }

    @Test
    void exposesDatabaseQueuedAndCompletedResultsThroughTheExistingResponseContract() throws Exception {
        User requestingUser = user("DB조회자");
        AnalysisJob analysisJob = accept(requestingUser.getId());
        queryAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.analysisId").value(analysisJob.id())).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1000));
        complete(analysisJob, 0);
        queryAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty()).andExpect(jsonPath("$.data.pollAfterMs").isEmpty())
                .andExpect(jsonPath("$.data.results[0].documentType").value("OTHER"));
    }

    @Test
    void listsDatabaseJobsNewestFirstWithoutExposingOtherOwners() throws Exception {
        User requestingUser = user("통합목록");
        AnalysisJob olderAnalysisJob = accept(requestingUser.getId());
        clock.set(clock.instant().plusSeconds(1).toString());
        AnalysisJob databaseAnalysisJob = accept(requestingUser.getId());
        accept(user("다른소유자").getId());
        listAnalyses(requestingUser.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.data.analyses.length()").value(2))
                .andExpect(jsonPath("$.data.analyses[0].analysisId").value(databaseAnalysisJob.id()))
                .andExpect(jsonPath("$.data.analyses[1].analysisId").value(olderAnalysisJob.id()));
        queryAnalysis(requestingUser.getId(), olderAnalysisJob.id()).andExpect(status().isOk());
    }

    @Test
    void preservesForbiddenAndNotFoundResponsesAndHidesExpiredResults() throws Exception {
        User requestingUser = user("권한확인");
        AnalysisJob analysisJob = accept(requestingUser.getId());
        queryAnalysis(user("타인").getId(), analysisJob.id()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GLOBAL-403-001"));
        queryAnalysis(requestingUser.getId(), "missing").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE-404-001"));
        complete(analysisJob, 0);
        clock.set(clock.instant().plus(Duration.ofDays(7)).toString());
        queryAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE-404-001"));
        listAnalyses(requestingUser.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.data.analyses").isEmpty());
    }

    @Test
    void rejectsDeletingAnActiveDatabaseJobAndDeletesTheFinishedJobWithItsChildren() throws Exception {
        User requestingUser = user("DB삭제자");
        AnalysisJob analysisJob = accept(requestingUser.getId());
        deleteAnalysis(user("다른삭제요청자").getId(), analysisJob.id()).andExpect(status().isForbidden());
        deleteAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMAGE-409-002"));
        complete(analysisJob, 0);
        deleteAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isNoContent());
        queryAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_outbox", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_requests", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states", Integer.class)).isZero();
    }

    @Test
    void expiresOnlyUnfinishedImagesThroughTheDatabaseUpdatePath() throws Exception {
        User requestingUser = user("기한확인");
        AnalysisJob analysisJob = analysisJobAcceptance.accept(requestingUser.getId(), 91L, "AUTO",
                List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32))));
        complete(analysisJob, 0);
        clock.set(clock.instant().plus(jobTimeout).minusMillis(1).toString());
        queryAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        clock.set(clock.instant().plusMillis(1).toString());
        queryAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PARTIALLY_COMPLETED"))
                .andExpect(jsonPath("$.data.results[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.results[1].error.code").value("ANALYSIS_EXPIRED"));
        AnalysisJob expiredAnalysisJob = analysisJobStateAccess.find(analysisJob.id()).orElseThrow();
        assertThat(expiredAnalysisJob.expiresAt().toInstant()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_job_states", String.class)).isEqualTo("PARTIALLY_COMPLETED");
        deleteAnalysis(requestingUser.getId(), analysisJob.id()).andExpect(status().isNoContent());
    }

    @Test
    void preservesACompletionSavedAfterTheInitialReadEvenWhenTheReadSnapshotIsOverdue() {
        AnalysisJob staleAnalysisJob = accept(user("완료경합").getId());
        complete(staleAnalysisJob, 0);
        AnalysisJob completedAnalysisJob = analysisJobStateAccess.find(staleAnalysisJob.id()).orElseThrow();
        clock.set(clock.instant().plus(jobTimeout).toString());
        assertThat(analysisDatabaseJobExpirer.expireIfOverdue(staleAnalysisJob)).isEqualTo(completedAnalysisJob);
        assertThat(analysisJobStateAccess.find(staleAnalysisJob.id())).contains(completedAnalysisJob);
    }

    private AnalysisJob accept(Long userId) {
        return analysisJobAcceptance.accept(userId, 91L, "AUTO", List.of(AnalysisImage.pending("database", "ab".repeat(32))));
    }

    private void complete(AnalysisJob analysisJob, int imageIndex) {
        analysisCompletionPersistence.apply(new AnalysisCompletedEvent(UUID.randomUUID().toString(), new AnalysisCompletedEvent.Payload("AnalysisCompleted",
                clock.instant(), analysisJob.analysisImages().get(imageIndex).requestId(), analysisJob.userId(), "ai-" + imageIndex, AnalysisStatus.COMPLETED,
                clock.instant(), new AnalysisCompletionSuccess(AnalysisDocumentType.OTHER, List.of()), null)));
    }

    private ResultActions deleteAnalysis(Long userId, String analysisJobId) throws Exception {
        Cookie csrfCookie = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mvc.perform(delete("/api/v1/image-analyses/{analysisId}", analysisJobId)
                .cookie(new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)), csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue()));
    }
}
