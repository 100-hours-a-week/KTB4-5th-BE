package com.dameokja.backend.analysis.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisExpirationDateType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedPayload;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisDatabaseIngredientRegistrationTest extends ServiceIntegrationTest {
    @Autowired AnalysisIngredientService service;
    @Autowired AnalysisJobAcceptance acceptance;
    @Autowired AnalysisCompletionPersistence completions;
    @Autowired AnalysisJobStateAccess states;
    private final List<AnalysisJob> jobs = new ArrayList<>();

    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_database_registration");
        for (AnalysisJob job : jobs) {
            jdbcTemplate.update("DELETE FROM analysis_feedbacks WHERE ai_analysis_id = ?", "ai-" + job.id());
            jdbcTemplate.update("DELETE FROM analysis_outbox WHERE analysis_job_id = ?", job.id());
            jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE analysis_job_id = ?", job.id());
            jdbcTemplate.update("DELETE FROM analysis_job_states WHERE analysis_job_id = ?", job.id());
        }
        super.cleanDatabaseAndResetTime();
    }

    @Test
    void commitsInventoryKafkaFeedbackAndJobConsumptionTogether() {
        Fixture owner = ownerFixture("DB등록자");
        AnalysisJob job = completedJob(owner);
        assertThat(service.create(owner.userId(), owner.refrigeratorId(), command(job)).createdCount()).isEqualTo(1);
        assertThat(states.find(job.id())).isEmpty();
        assertThat(count("ingredients")).isEqualTo(1);
        assertThat(count("analysis_feedbacks")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(message_body, '$.payload.requestId')) FROM analysis_feedbacks", String.class))
                .isEqualTo(job.analysisImages().getFirst().requestId());
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_EXTRACT(message_body, '$.payload.userId') FROM analysis_feedbacks", Long.class)).isEqualTo(owner.userId());
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(message_body, '$.payload.items[0].confirmed.name')) FROM analysis_feedbacks", String.class))
                .isEqualTo("확정우유");
        assertThat(count("analysis_job_requests")).isZero();
        assertThat(count("analysis_outbox")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FEEDBACK", "DELETE"})
    void preservesTheResultAndRollsBackInventoryAndFeedbackOnFailure(String failure) {
        Fixture owner = ownerFixture("DB롤백");
        AnalysisJob job = completedJob(owner);
        String target = failure.equals("FEEDBACK") ? "BEFORE INSERT ON analysis_feedbacks" : "BEFORE DELETE ON analysis_job_states";
        jdbcTemplate.execute("CREATE TRIGGER fail_database_registration " + target + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'registration-rollback'");
        assertThatThrownBy(() -> service.create(owner.userId(), owner.refrigeratorId(), command(job))).hasStackTraceContaining("registration-rollback");
        assertThat(states.find(job.id())).contains(job);
        assertThat(count("ingredients")).isZero();
        assertThat(count("analysis_feedbacks")).isZero();
        assertThat(count("analysis_job_requests")).isEqualTo(1);
        assertThat(count("analysis_outbox")).isEqualTo(1);
    }

    @Test
    void consumesTheSameJobOnlyOnceWhenRegistrationsCompete() throws Exception {
        Fixture owner = ownerFixture("DB동시등록");
        AnalysisJob job = completedJob(owner);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> registerAfter(start, owner, job));
            Future<Object> second = executor.submit(() -> registerAfter(start, owner, job));
            List<Object> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result instanceof CustomException).toList()).singleElement()
                    .satisfies(result -> assertThat(((CustomException) result).getExceptionCode().getCode()).isEqualTo("IMAGE-404-001"));
        }
        assertThat(count("ingredients")).isEqualTo(1);
        assertThat(count("analysis_feedbacks")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM ingredients", Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsAnExpiredDatabaseResultWithoutFallingBackToLegacyStorage() {
        Fixture owner = ownerFixture("DB만료등록");
        AnalysisJob job = completedJob(owner);
        clock.set(job.expiresAt().toInstant().toString());
        assertThatThrownBy(() -> service.create(owner.userId(), owner.refrigeratorId(), command(job)))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getExceptionCode().getCode()).isEqualTo("IMAGE-404-001"));
        assertThat(count("ingredients")).isZero();
        assertThat(count("analysis_feedbacks")).isZero();
        assertThat(count("analysis_job_states")).isEqualTo(1);
    }

    @Test
    void rejectsAnotherOwnersDatabaseResult() {
        Fixture owner = ownerFixture("DB소유자");
        Fixture other = ownerFixture("DB다른소유자");
        AnalysisJob job = completedJob(owner);
        assertThatThrownBy(() -> service.create(other.userId(), other.refrigeratorId(), command(job)))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getExceptionCode().getCode()).isEqualTo("GLOBAL-403-001"));
        assertThat(states.find(job.id())).contains(job);
        assertThat(count("ingredients")).isZero();
    }

    @Test
    void rejectsRegistrationIntoAnotherSelectedRefrigerator() {
        Fixture owner = ownerFixture("DB냉장고확인");
        AnalysisJob job = completedJob(owner);
        jdbcTemplate.update("UPDATE refrigerator_members SET is_active = 0 WHERE user_id = ?", owner.userId());
        com.dameokja.backend.refrigerator.domain.Refrigerator other = refrigerator("다른 냉장고");
        join(userRepository.getReferenceById(owner.userId()), other, true, true);
        assertThatThrownBy(() -> service.create(owner.userId(), other.getId(), command(job)))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getExceptionCode().getCode()).isEqualTo("IMAGE-400-002"));
        assertThat(states.find(job.id())).contains(job);
        assertThat(count("ingredients")).isZero();
    }

    private Object registerAfter(CyclicBarrier start, Fixture owner, AnalysisJob job) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            return service.create(owner.userId(), owner.refrigeratorId(), command(job));
        } catch (CustomException exception) {
            return exception;
        }
    }

    private AnalysisJob completedJob(Fixture owner) {
        AnalysisJob job = acceptance.accept(owner.userId(), "PRODUCT", List.of(AnalysisImage.pending("image", "ab".repeat(32))));
        jobs.add(job);
        AnalysisCompletionItem item = new AnalysisCompletionItem("item", RecognitionStatus.RECOGNIZED, "우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 2, new AnalysisCompletionItem.Weight(null, WeightUnit.NONE),
                new AnalysisCompletionItem.Expiration(BusinessTime.today(clock).plusDays(3), AnalysisExpirationDateType.USE_BY, null), List.of());
        return completions.apply(EventEnvelope.create(new AnalysisCompletedPayload(clock.instant(), job.analysisImages().getFirst().requestId(), owner.userId(),
                owner.refrigeratorId(), "ai-" + job.id(), AnalysisStatus.COMPLETED, clock.instant(), new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of(item)), null)))
                .getFirst();
    }

    private AnalysisIngredientCommand command(AnalysisJob job) {
        return new AnalysisIngredientCommand(job.id(), List.of(new AnalysisIngredientCommand.Image("image", List.of(
                new AnalysisIngredientCommand.Item("item", "확정우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT,
                        BigDecimal.TWO, null, BusinessTime.today(clock).plusDays(3))))));
    }

    private int count(String table) {
        if (!List.of("ingredients", "analysis_feedbacks", "analysis_job_states", "analysis_job_requests", "analysis_outbox").contains(table)) {
            throw new IllegalArgumentException("검증 대상이 아닌 테이블입니다.");
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
