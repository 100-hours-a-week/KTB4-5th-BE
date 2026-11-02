package com.dameokja.backend.analysis.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaConfig;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisRequestedEvent;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({AnalysisJobAcceptance.class, UserAccessService.class, AnalysisKafkaConfig.class, AnalysisJobAcceptanceTest.Config.class})
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
    void createOwner() { requestingUser = userRepository.saveAndFlush(new User("접수자", "profile")); }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_analysis_acceptance");
        jdbcTemplate.update("DELETE FROM analysis_outbox");
        jdbcTemplate.update("DELETE FROM analysis_job_requests");
        jdbcTemplate.update("DELETE FROM analysis_job_states");
        userRepository.deleteById(requestingUser.getId());
    }

    @Test
    void commitsTheQueuedJobAndOneFixedEventPerImage() {
        AnalysisJob analysisJob = analysisJobAcceptance.accept(requestingUser.getId(), 91L, "RECEIPT", analysisImages);
        assertStoredCounts(1, 2);
        assertThat(analysisJob.submittedAt().toInstant()).isEqualTo(SUBMITTED_AT);
        assertThat(analysisJob.expiresAt()).isNull();
        assertThat(analysisJob.refrigeratorId()).isEqualTo(91L);
        assertThat(objectMapper.readTree(jdbcTemplate.queryForObject("SELECT job_body FROM analysis_job_states", String.class)))
                .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(analysisJob)));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_job_states", String.class)).isEqualTo("QUEUED");
        List<AnalysisRequestedEvent> analysisRequestedEvents = jdbcTemplate.query("SELECT message_body FROM analysis_outbox ORDER BY analysis_outbox_id",
                (resultSet, rowNumber) -> objectMapper.readValue(resultSet.getString(1), AnalysisRequestedEvent.class));
        assertThat(analysisRequestedEvents).extracting(analysisRequestedEvent -> analysisRequestedEvent.payload().requestId())
                .containsExactly(analysisImages.getFirst().requestId(), analysisImages.getLast().requestId());
        assertThat(analysisRequestedEvents).extracting(AnalysisRequestedEvent::messageId).doesNotHaveDuplicates();
        assertThat(analysisRequestedEvents).allSatisfy(analysisRequestedEvent -> {
            assertThat(analysisRequestedEvent.payload().userId()).isEqualTo(requestingUser.getId());
            assertThat(analysisRequestedEvent.payload().refrigeratorId()).isEqualTo(91L);
            assertThat(analysisRequestedEvent.payload().occurredAt()).isEqualTo(SUBMITTED_AT);
            assertThat(analysisRequestedEvent.payload().inputHint()).isEqualTo("RECEIPT");
        });
        assertThat(jdbcTemplate.queryForList("SELECT message_id FROM analysis_outbox ORDER BY analysis_outbox_id", String.class))
                .containsExactlyElementsOf(analysisRequestedEvents.stream().map(AnalysisRequestedEvent::messageId).toList());
        assertThat(jdbcTemplate.queryForList("SELECT message_key FROM analysis_outbox", String.class)).containsOnly(requestingUser.getId().toString());
        assertThat(jdbcTemplate.queryForList("SELECT status FROM analysis_outbox", String.class)).containsOnly("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"analysis_job_states", "analysis_job_requests", "analysis_outbox"})
    void rollsBackBothStoresWhenEitherInsertFails(String tableName) {
        String insertFailureSignal = "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'atomicity-probe';";
        String failureTriggerBody = tableName.equals("analysis_outbox")
                ? "IF JSON_UNQUOTE(JSON_EXTRACT(NEW.message_body, '$.payload.image.objectKey')) = 'second' THEN " + insertFailureSignal + " END IF;" : insertFailureSignal;
        jdbcTemplate.execute("CREATE TRIGGER fail_analysis_acceptance BEFORE INSERT ON " + tableName + " FOR EACH ROW BEGIN " + failureTriggerBody + " END");

        assertThatThrownBy(() -> analysisJobAcceptance.accept(requestingUser.getId(), 91L, "AUTO", analysisImages)).hasStackTraceContaining("atomicity-probe");
        assertStoredCounts(0, 0);
    }

    @Test
    void joinsTheCallersTransactionInsteadOfCommittingTheOutboxSeparately() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            analysisJobAcceptance.accept(requestingUser.getId(), 91L, "AUTO", analysisImages);
            assertStoredCounts(1, 2);
            transactionStatus.setRollbackOnly();
        });
        assertStoredCounts(0, 0);
    }

    private void assertStoredCounts(int expectedJobCount, int expectedOutboxCount) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states", Integer.class)).isEqualTo(expectedJobCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_outbox", Integer.class)).isEqualTo(expectedOutboxCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_requests", Integer.class)).isEqualTo(expectedOutboxCount);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean @Primary Clock acceptanceClock() { return Clock.fixed(SUBMITTED_AT, ZoneOffset.UTC); }
    }
}
