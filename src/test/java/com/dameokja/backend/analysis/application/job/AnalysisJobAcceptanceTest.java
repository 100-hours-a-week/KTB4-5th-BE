package com.dameokja.backend.analysis.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaConfig;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisRequestedPayload;
import com.dameokja.backend.analysis.support.AnalysisRefrigeratorFixture;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({RefrigeratorAccessService.class, AnalysisJobAcceptance.class, UserAccessService.class, AnalysisKafkaConfig.class, AnalysisJobAcceptanceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisJobAcceptanceTest extends MySqlJpaTest {
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-29T03:00:00Z");
    private final List<AnalysisImage> analysisImages = List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32)));
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;
    private User requestingUser;

    @BeforeEach
    void createOwner() {
        requestingUser = userRepository.saveAndFlush(new User("접수" + UUID.randomUUID().toString().substring(0, 6), "profile"));
        AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, requestingUser.getId());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_analysis_acceptance");
        jdbcTemplate.update("DELETE FROM analysis_outbox WHERE analysis_job_id IN (SELECT analysis_job_id FROM analysis_job_states WHERE user_id = ?)", requestingUser.getId());
        jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE analysis_job_id IN (SELECT analysis_job_id FROM analysis_job_states WHERE user_id = ?)", requestingUser.getId());
        jdbcTemplate.update("DELETE FROM analysis_job_states WHERE user_id = ?", requestingUser.getId());
        AnalysisRefrigeratorFixture.cleanUp(jdbcTemplate, requestingUser.getId());
        userRepository.deleteById(requestingUser.getId());
    }

    @Test
    void commitsTheQueuedJobAndOneFixedEventPerImage() {
        AnalysisJob analysisJob = analysisJobAcceptance.accept(requestingUser.getId(), "RECEIPT", analysisImages);
        assertStoredCounts(1, 2);
        assertThat(analysisJob.submittedAt().toInstant()).isEqualTo(SUBMITTED_AT);
        assertThat(analysisJob.expiresAt()).isNull();
        assertThat(objectMapper.readTree(jdbcTemplate.queryForObject("SELECT job_body FROM analysis_job_states WHERE user_id = ?", String.class, requestingUser.getId())))
                .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(analysisJob)));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_job_states WHERE user_id = ?", String.class, requestingUser.getId())).isEqualTo("QUEUED");
        List<EventEnvelope<AnalysisRequestedPayload>> analysisRequestedEvents = jdbcTemplate.query("SELECT message_body FROM analysis_outbox WHERE analysis_job_id = ? ORDER BY analysis_outbox_id",
                (resultSet, rowNumber) -> objectMapper.readValue(resultSet.getString(1), new TypeReference<EventEnvelope<AnalysisRequestedPayload>>() {}), analysisJob.id());
        assertThat(analysisRequestedEvents).extracting(analysisRequestedEvent -> analysisRequestedEvent.payload().requestId())
                .containsExactly(analysisJob.analysisImages().getFirst().requestId(), analysisJob.analysisImages().getLast().requestId());
        assertThat(analysisRequestedEvents).extracting(EventEnvelope::messageId).doesNotHaveDuplicates();
        assertThat(analysisRequestedEvents).allSatisfy(analysisRequestedEvent -> {
            assertThat(analysisRequestedEvent.payload().refrigeratorId()).isEqualTo(analysisJob.refrigeratorId());
            assertThat(analysisRequestedEvent.payload().userId()).isEqualTo(requestingUser.getId());
            assertThat(analysisRequestedEvent.payload().occurredAt()).isEqualTo(SUBMITTED_AT);
            assertThat(analysisRequestedEvent.payload().inputHint()).isEqualTo("RECEIPT");
        });
        assertThat(jdbcTemplate.queryForList("SELECT message_id FROM analysis_outbox WHERE analysis_job_id = ? ORDER BY analysis_outbox_id", String.class, analysisJob.id()))
                .containsExactlyElementsOf(analysisRequestedEvents.stream().map(EventEnvelope::messageId).toList());
        assertThat(jdbcTemplate.queryForList("SELECT message_key FROM analysis_outbox WHERE analysis_job_id = ?", String.class, analysisJob.id())).containsOnly(requestingUser.getId().toString());
        assertThat(jdbcTemplate.queryForList("SELECT status FROM analysis_outbox WHERE analysis_job_id = ?", String.class, analysisJob.id())).containsOnly("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"analysis_job_states", "analysis_job_requests", "analysis_outbox"})
    void rollsBackBothStoresWhenEitherInsertFails(String tableName) {
        String insertFailureSignal = "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'atomicity-probe';";
        String failureTriggerBody = tableName.equals("analysis_outbox")
                ? "IF JSON_UNQUOTE(JSON_EXTRACT(NEW.message_body, '$.payload.image.objectKey')) = 'second' THEN " + insertFailureSignal + " END IF;" : insertFailureSignal;
        jdbcTemplate.execute("CREATE TRIGGER fail_analysis_acceptance BEFORE INSERT ON " + tableName + " FOR EACH ROW BEGIN " + failureTriggerBody + " END");

        assertThatThrownBy(() -> analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", analysisImages)).hasStackTraceContaining("atomicity-probe");
        assertStoredCounts(0, 0);
    }

    @Test
    void joinsTheCallersTransactionInsteadOfCommittingTheOutboxSeparately() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", analysisImages);
            assertStoredCounts(1, 2);
            transactionStatus.setRollbackOnly();
        });
        assertStoredCounts(0, 0);
    }

    @Test
    void scopesRequestIdentityToTheSelectedRefrigeratorAndHint() {
        AnalysisJob first = analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", analysisImages);
        AnalysisJob repeated = analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", analysisImages);
        AnalysisJob differentHint = analysisJobAcceptance.accept(requestingUser.getId(), "PRODUCT", analysisImages);
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active = 0 WHERE user_id = ?", requestingUser.getId());
        Long selected = AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, requestingUser.getId());
        AnalysisJob switched = analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", analysisImages);
        assertThat(repeated.analysisImages()).isEqualTo(first.analysisImages());
        assertThat(differentHint.analysisImages().getFirst().requestId()).isNotEqualTo(first.analysisImages().getFirst().requestId());
        assertThat(switched.refrigeratorId()).isEqualTo(selected).isNotEqualTo(first.refrigeratorId());
        assertThat(switched.analysisImages().getFirst().requestId()).isNotEqualTo(first.analysisImages().getFirst().requestId());
    }

    @Test
    void rejectsRequestsWithoutASelectedRefrigeratorWithoutSavingAnything() {
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active = 0 WHERE user_id = ?", requestingUser.getId());
        assertThatThrownBy(() -> analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", analysisImages))
                .isInstanceOf(com.dameokja.backend.global.exception.CustomException.class);
        assertStoredCounts(0, 0);
    }

    private void assertStoredCounts(int expectedJobCount, int expectedOutboxCount) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states WHERE user_id = ?", Integer.class, requestingUser.getId()))
                .isEqualTo(expectedJobCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_outbox WHERE analysis_job_id IN "
                + "(SELECT analysis_job_id FROM analysis_job_states WHERE user_id = ?)", Integer.class, requestingUser.getId())).isEqualTo(expectedOutboxCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_requests WHERE analysis_job_id IN "
                + "(SELECT analysis_job_id FROM analysis_job_states WHERE user_id = ?)", Integer.class, requestingUser.getId())).isEqualTo(expectedOutboxCount);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean @Primary Clock acceptanceClock() { return Clock.fixed(SUBMITTED_AT, ZoneOffset.UTC); }
    }
}
