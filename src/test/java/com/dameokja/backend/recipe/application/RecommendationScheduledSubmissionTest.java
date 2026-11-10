package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.application.RecommendationDailyJobQueue.Lease;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = "recommendation.reuse.enabled=true")
class RecommendationScheduledSubmissionTest extends ServiceIntegrationTest {
    @Autowired private RecommendationScheduledSubmissionService scheduled;
    @Autowired private RecommendationScheduledAcceptanceService acceptance;
    @Autowired private RecommendationDailyJobQueue queue;
    @Autowired private RecommendationInventoryService inventory;
    @Autowired private RecommendationSubmissionService manual;
    @Autowired private RecommendationCompletionService completions;
    @Autowired private RecommendationSelectionService selections;
    @Autowired private RecommendationOutboxService outbox;
    @Autowired private RecommendationStateRepository states;
    @Autowired private RecommendationPreferenceService preferences;
    @Autowired private RecommendationDailyQuotaService quota;
    @Autowired private MembershipService memberships;
    @Autowired private InvitationService invitations;
    @Autowired private JsonMapper mapper;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsOnceUsingLastPreferencesOrDefaultsWithoutCharging(boolean hasPreference) {
        Fixture participant = ownerFixture("자동접수");
        RecommendationOptions options = RecommendationOptions.fromInput(null, List.of("한식"), null, null, null, null, null, null).effective();
        if (hasPreference) { remember(participant, options); }
        Lease lease = lease(participant);
        scheduled.submit(lease);
        scheduled.submit(lease);
        assertThat(status(lease)).isEqualTo("ACCEPTED");
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(state(participant).getActiveRequestId()).isEqualTo(lease.requestId());
        assertThat(state(participant).getGenerationTrigger()).isEqualTo(RecommendationState.GenerationTrigger.SCHEDULED);
        assertThat(state(participant).getCurrentList()).isNull();
        assertThat(quota.current(participant.userId()).remaining()).isEqualTo(10);
        String payload = jdbcTemplate.queryForObject("SELECT payload FROM recommendation_outbox", String.class);
        assertThat(RecommendationOptions.from(mapper.readValue(payload, AiRecipeRecommendationRequest.class)).effective())
                .isEqualTo(hasPreference ? options : RecommendationOptions.empty().effective());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusesAnUnselectedResultOrRegeneratesWhenInventoryChanged(boolean changedInventory) throws Exception {
        Fixture participant = ownerFixture("자동이력");
        complete(manual.submit(participant.userId(), participant.refrigeratorId(), null));
        Long previousList = state(participant).getLatestList().getId();
        if (changedInventory) {
            jdbcTemplate.update("INSERT INTO ingredients (refrigerator_id, owner_id, name, category, quantity, expiration_date, registration_source) "
                    + "VALUES (?, ?, '두부', 'TOFU_BEAN', 1, '2026-12-01', 'DIRECT')", participant.refrigeratorId(), participant.userId());
        }
        Lease lease = lease(participant);
        scheduled.submit(lease);
        assertThat(status(lease)).isEqualTo(changedInventory ? "ACCEPTED" : "REUSED");
        assertThat(outboxCount()).isEqualTo(changedInventory ? 2 : 1);
        assertThat(state(participant).getCurrentList()).isNull();
        assertThat(state(participant).getLatestList().getId()).isEqualTo(previousList);
        assertThat(quota.current(participant.userId()).remaining()).isEqualTo(9);
        if (!changedInventory) {
            assertThat(jdbcTemplate.queryForObject("SELECT recommendation_list_id FROM recommendation_daily_jobs", Long.class)).isEqualTo(previousList);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING", "YESTERDAY"})
    void skipsOnlyTodayManualGeneration(String progress) {
        Fixture participant = ownerFixture("자동건너뜀");
        RecommendationSubmissionResult userRequest = manual.submit(participant.userId(), participant.refrigeratorId(), null);
        boolean yesterday = "YESTERDAY".equals(progress);
        if (yesterday) { clock.set("2026-09-17T15:00:00Z"); }
        else { jdbcTemplate.update("UPDATE recommendation_states SET generation_status = ? WHERE user_id = ?", progress, participant.userId()); }
        Lease lease = lease(participant);
        scheduled.submit(lease);
        assertThat(status(lease)).isEqualTo(yesterday ? "ACCEPTED" : "SKIPPED");
        assertThat(state(participant).getActiveRequestId()).isEqualTo(yesterday ? lease.requestId() : userRequest.requestId());
        assertThat(outboxCount()).isEqualTo(yesterday ? 2 : 1);
    }

    @Test
    void preservesTheCurrentListWhileReplacingAnUnselectedGeneration() throws Exception {
        Fixture participant = ownerFixture("자동선택보존");
        complete(manual.submit(participant.userId(), participant.refrigeratorId(), null));
        selections.selectLatest(participant.userId(), participant.refrigeratorId());
        Long current = state(participant).getCurrentList().getId();
        RecommendationOptions options = RecommendationOptions.fromInput(null, List.of("한식"), null, null, null, null, null, null);
        complete(manual.submit(participant.userId(), participant.refrigeratorId(), options));
        Long latest = state(participant).getLatestList().getId();
        jdbcTemplate.update("INSERT INTO ingredients (refrigerator_id, owner_id, name, category, quantity, expiration_date, registration_source) "
                + "VALUES (?, ?, '두부', 'TOFU_BEAN', 1, '2026-12-01', 'DIRECT')", participant.refrigeratorId(), participant.userId());
        Lease lease = lease(participant);
        scheduled.submit(lease);
        assertThat(status(lease)).isEqualTo("ACCEPTED");
        assertThat(state(participant).getCurrentList().getId()).isEqualTo(current);
        assertThat(state(participant).getLatestList().getId()).isEqualTo(latest);
        assertThat(quota.current(participant.userId()).remaining()).isEqualTo(8);
    }

    @Test
    void rejectsAnInputWithADifferentRequestIdWithoutChangingTheJob() {
        Fixture participant = ownerFixture("자동식별검증");
        Lease lease = lease(participant);
        AiRecipeRecommendationRequest other = inventory.prepare(participant.userId(), participant.refrigeratorId(), RecommendationOptions.empty());
        assertThatThrownBy(() -> acceptance.accept(lease, other)).isInstanceOf(IllegalArgumentException.class);
        assertThat(status(lease)).isEqualTo("CLAIMED");
        assertThat(states.count()).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void rechecksMembershipAfterPreparingInventoryAndSkipsTheOldRefrigerator() {
        Fixture participant = ownerFixture("자동이동");
        Lease lease = lease(participant);
        AiRecipeRecommendationRequest prepared = prepare(lease);
        Fixture sharedOwner = ownerFixture("공유주인");
        memberships.join(participant.userId(), invitations.issueCode(sharedOwner.userId(), sharedOwner.refrigeratorId()).code());
        assertThatThrownBy(() -> acceptance.accept(lease, prepared)).isInstanceOf(CustomException.class);
        scheduled.submit(lease);
        assertThat(status(lease)).isEqualTo("SKIPPED");
        assertThat(outboxCount()).isZero();
        assertThat(states.count()).isZero();
    }

    @Test
    void rejectsTheExpiredOwnerAndPreservesTheRequestIdAfterRecovery() {
        Lease old = lease(ownerFixture("자동회수"));
        AiRecipeRecommendationRequest prepared = prepare(old);
        clock.set("2026-09-17T03:00:30Z");
        Lease recovered = queue.leaseBatch(old.businessDate(), 1, Duration.ofSeconds(30)).getFirst();
        acceptance.accept(old, prepared);
        assertThat(status(recovered)).isEqualTo("CLAIMED");
        assertThat(outboxCount()).isZero();
        acceptance.accept(recovered, prepared);
        assertThat(jdbcTemplate.queryForObject("SELECT request_id FROM recommendation_outbox", String.class)).isEqualTo(old.requestId());
    }

    @Test
    void skipsWhenTheBusinessDateChangesBeforeAcceptance() {
        clock.set("2026-09-17T14:59:00Z");
        Fixture participant = ownerFixture("자동날짜");
        queue.register(participant.userId(), participant.refrigeratorId(), BusinessTime.today(clock));
        Lease lease = queue.leaseBatch(BusinessTime.today(clock), 1, Duration.ofMinutes(5)).getFirst();
        AiRecipeRecommendationRequest prepared = prepare(lease);
        clock.set("2026-09-17T15:00:00Z");
        acceptance.accept(lease, prepared);
        assertThat(status(lease)).isEqualTo("SKIPPED");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void rollsBackAllAcceptanceWritesAndRetriesWithTheSameRequestId() {
        Lease lease = lease(ownerFixture("자동롤백"));
        jdbcTemplate.execute("CREATE TRIGGER reject_daily_outbox BEFORE INSERT ON recommendation_outbox "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'daily rollback'");
        try { assertThatThrownBy(() -> scheduled.submit(lease)).hasStackTraceContaining("daily rollback"); }
        finally { jdbcTemplate.execute("DROP TRIGGER reject_daily_outbox"); }
        assertThat(status(lease)).isEqualTo("CLAIMED");
        assertThat(states.count()).isZero();
        assertThat(outboxCount()).isZero();
        scheduled.submit(lease);
        assertThat(status(lease)).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT request_id FROM recommendation_outbox", String.class)).isEqualTo(lease.requestId());
    }

    @Test
    void serializesManualAndAutomaticRequestsForTheSameParticipant() throws Exception {
        Fixture participant = ownerFixture("자동경합");
        Lease lease = lease(participant);
        List<Object> results = concurrently(List.of(() -> manual.submit(participant.userId(), participant.refrigeratorId(), null),
                () -> {
                    scheduled.submit(lease);
                    return "scheduled";
                }));
        assertThat(results.stream().filter(Throwable.class::isInstance)).allMatch(error -> error instanceof CustomException exception
                && exception.getExceptionCode() == RecommendationExceptionCode.GENERATION_IN_PROGRESS);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(status(lease)).isIn("ACCEPTED", "SKIPPED");
    }

    @Test
    void doesNotOverwritePreferencesChangedAfterAutomaticPreparation() {
        Fixture participant = ownerFixture("자동취향보존");
        Lease lease = lease(participant);
        AiRecipeRecommendationRequest prepared = prepare(lease);
        RecommendationOptions changed = RecommendationOptions.fromInput(null, List.of("한식"), null, null, null, null, null, null).effective();
        remember(participant, changed);
        acceptance.accept(lease, prepared);
        assertThat(preferences.last(participant.userId())).isEqualTo(changed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "SUPERSEDED"})
    void recordsTerminalResultsWithoutChangingASupersedingRequest(String outcome) throws Exception {
        Fixture participant = ownerFixture("자동완료");
        Lease lease = lease(participant);
        scheduled.submit(lease);
        String activeRequest = lease.requestId();
        if ("SUPERSEDED".equals(outcome)) {
            clock.set("2026-09-17T15:00:00Z");
            activeRequest = manual.submit(participant.userId(), participant.refrigeratorId(), null).requestId();
        }
        ObjectNode response = completedResponse(outbox.request(jdbcTemplate.queryForObject(
                "SELECT event_id FROM recommendation_outbox WHERE request_id = ?", String.class, lease.requestId())));
        if ("FAILED".equals(outcome)) {
            response.put("status", "FAILED").putNull("result");
            response.putObject("error").put("code", "AI_FAILED").put("message", "failure").put("retryable", false);
        }
        completions.apply(mapper.treeToValue(response, AiRecipeJobResponse.class));
        completions.apply(mapper.treeToValue(response, AiRecipeJobResponse.class));
        assertThat(status(lease)).isEqualTo("COMPLETED".equals(outcome) ? "COMPLETED" : "FAILED");
        assertThat(state(participant).getCurrentList()).isNull();
        if ("SUPERSEDED".equals(outcome)) { assertThat(state(participant).getActiveRequestId()).isEqualTo(activeRequest); }
        if ("COMPLETED".equals(outcome)) {
            assertThat(jdbcTemplate.queryForObject("SELECT recommendation_list_id FROM recommendation_daily_jobs", Long.class))
                    .isEqualTo(state(participant).getLatestList().getId());
        }
        assertThat(queue.leaseBatch(lease.businessDate(), 1, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void rollsBackRecommendationAndDailyCompletionTogether() throws Exception {
        Fixture participant = ownerFixture("자동완료롤백");
        Lease lease = lease(participant);
        scheduled.submit(lease);
        AiRecipeJobResponse response = mapper.treeToValue(completedResponse(outbox.request(jdbcTemplate.queryForObject(
                "SELECT event_id FROM recommendation_outbox WHERE request_id = ?", String.class, lease.requestId()))), AiRecipeJobResponse.class);
        jdbcTemplate.execute("CREATE TRIGGER reject_daily_completion BEFORE UPDATE ON recommendation_daily_jobs FOR EACH ROW BEGIN "
                + "IF NEW.status = 'COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'completion rollback'; END IF; END");
        try { assertThatThrownBy(() -> completions.apply(response)).hasStackTraceContaining("completion rollback"); }
        finally { jdbcTemplate.execute("DROP TRIGGER reject_daily_completion"); }
        assertThat(status(lease)).isEqualTo("ACCEPTED");
        assertThat(state(participant).getLatestList()).isNull();
        assertThat(state(participant).getActiveRequestId()).isEqualTo(lease.requestId());
        completions.apply(response);
        assertThat(status(lease)).isEqualTo("COMPLETED");
    }

    private Lease lease(Fixture participant) {
        queue.register(participant.userId(), participant.refrigeratorId(), BusinessTime.today(clock));
        return queue.leaseBatch(BusinessTime.today(clock), 1, Duration.ofSeconds(30)).getFirst();
    }

    private AiRecipeRecommendationRequest prepare(Lease lease) {
        return inventory.prepare(lease.userId(), lease.refrigeratorId(), preferences.last(lease.userId()), lease.requestId());
    }

    private void complete(RecommendationSubmissionResult submission) throws Exception {
        AiRecipeRecommendationRequest request = outbox.request(submission.eventId());
        completions.apply(mapper.treeToValue(completedResponse(request), AiRecipeJobResponse.class));
    }

    private ObjectNode completedResponse(AiRecipeRecommendationRequest request) throws Exception {
        ObjectNode response = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/recipe-ai/wiki-completed-response.json"));
        response.put("requestId", request.requestId()).put("userId", request.userId()).put("refrigeratorId", request.refrigeratorId());
        ((ObjectNode) response.get("result")).put("userId", request.userId()).put("refrigeratorId", request.refrigeratorId())
                .put("inventoryFingerprint", request.inventoryFingerprint()).put("recommendationMode", request.effectiveMode().name());
        return response;
    }

    private void remember(Fixture participant, RecommendationOptions options) {
        RedisTestSupport.redisTemplate().opsForValue().set("recipe:preferences:" + participant.userId(), mapper.writeValueAsString(options));
    }

    private RecommendationState state(Fixture participant) { return states.findByUserIdAndRefrigeratorId(participant.userId(), participant.refrigeratorId()).orElseThrow(); }

    private String status(Lease lease) { return jdbcTemplate.queryForObject("SELECT status FROM recommendation_daily_jobs WHERE recommendation_daily_job_id = ?", String.class, lease.jobId()); }

    private int outboxCount() { return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class); }
}
