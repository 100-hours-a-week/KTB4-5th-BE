package com.dameokja.backend.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.domain.AnalysisJobRequest;
import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

class AnalysisJobStateRepositoryTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);
    @Autowired AnalysisJobStateRepository analysisJobStateRepository;
    @Autowired AnalysisJobRequestRepository analysisJobRequestRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reloadsJsonSnapshotAndFindsAllJobsSharingAnImageRequest() {
        User user = new User("분석회원", "profile/test.png");
        entityManager.persist(user);
        AnalysisJobState first = saveJob(user, 17L);
        AnalysisJobState second = saveJob(user, 17L);
        String requestId = UUID.randomUUID().toString();
        AnalysisJobRequest request = analysisJobRequestRepository.save(new AnalysisJobRequest(first, requestId));
        analysisJobRequestRepository.save(new AnalysisJobRequest(second, requestId));
        flushAndClear();
        AnalysisJobState restored = analysisJobStateRepository.findById(first.getId()).orElseThrow();
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(restored.getAnalysisJobBody())).isEqualTo(mapper.readTree(first.getAnalysisJobBody()));
        assertThat(restored.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(restored.getCreatedAt()).isNotNull();
        assertThat(restored.getVersion()).isZero();
        assertThat(analysisJobRequestRepository.findAnalysisJobIds(requestId)).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(analysisJobRequestRepository.findIdsByAnalysisJobId(first.getId())).containsExactly(request.getId());
        assertThatThrownBy(() -> analysisJobRequestRepository.saveAndFlush(new AnalysisJobRequest(restored, requestId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void excludesExpiredJobsAndFiltersOwnerAndRefrigerator() {
        User user = new User("분석회원", "profile/test.png");
        User other = new User("다른회원", "profile/other.png");
        entityManager.persist(user);
        entityManager.persist(other);
        AnalysisJobState active = saveJob(user, 17L);
        AnalysisJobState expired = saveJob(user, 17L);
        expired.updateResult(expired.getAnalysisJobBody(), AnalysisStatus.COMPLETED, NOW);
        AnalysisJobState differentFridge = saveJob(user, 18L);
        AnalysisJobState foreign = saveJob(other, 17L);
        flushAndClear();
        assertThat(analysisJobStateRepository.findAllActiveOwned(user.getId(), NOW)).extracting(AnalysisJobState::getId)
                .containsExactlyInAnyOrder(active.getId(), differentFridge.getId());
        assertThat(analysisJobStateRepository.findAllActiveOwnedInRefrigerator(user.getId(), 17L, NOW))
                .extracting(AnalysisJobState::getId).containsExactly(active.getId());
        assertThat(analysisJobStateRepository.findActiveOwnedInRefrigerator(active.getId(), user.getId(), 18L, NOW)).isEmpty();
        assertThat(analysisJobStateRepository.findActiveOwnedInRefrigerator(foreign.getId(), user.getId(), 17L, NOW)).isEmpty();
        assertThat(analysisJobStateRepository.existsActiveOwnedByAnotherUser(foreign.getId(), user.getId(), NOW)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migrationCreatesBothTablesAndTheirForeignKey() throws Exception {
        String sql = new ClassPathResource("db/migrations/20261104-analysis-job-state-storage.sql")
                .getContentAsString(StandardCharsets.UTF_8).replace("analysis_job_states", "analysis_job_states_migration_test")
                .replace("analysis_job_requests", "analysis_job_requests_migration_test")
                .replace("fk_analysis_job_", "fk_migration_job_").replace("ck_analysis_job_", "ck_migration_job_");
        try {
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job_states_migration_test", Integer.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("INSERT INTO analysis_job_requests_migration_test (request_id, analysis_job_id) VALUES (?, ?)",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString())).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS analysis_job_requests_migration_test");
            jdbc.execute("DROP TABLE IF EXISTS analysis_job_states_migration_test");
        }
    }

    private AnalysisJobState saveJob(User user, Long refrigeratorId) {
        return analysisJobStateRepository.save(AnalysisJobState.queued(user, UUID.randomUUID().toString(), "{\"refrigeratorId\":" + refrigeratorId + ",\"images\":[]}"));
    }
}
