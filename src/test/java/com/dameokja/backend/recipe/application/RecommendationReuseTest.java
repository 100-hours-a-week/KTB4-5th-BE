package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = "recommendation.reuse.enabled=true")
class RecommendationReuseTest extends ServiceIntegrationTest {
    @Autowired private RecommendationSubmissionService submissions;
    @Autowired private RecommendationCompletionService completions;
    @Autowired private RecommendationSelectionService selections;
    @Autowired private RecommendationOutboxService outbox;
    @Autowired private RecommendationStateRepository states;
    @Autowired private RecommendationListRepository lists;
    @Autowired private MembershipService memberships;
    @Autowired private InvitationService invitations;
    @Autowired private JsonMapper mapper;
    @Autowired private RecommendationPreferenceService preferences;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private StringRedisTemplate redis;

    @Test
    void reusesEarlierMatchingHistoryWithoutChangingSelectionOrChargingQuota() throws Exception {
        Fixture participant = ownerFixture("이력재사용");
        RecommendationSubmissionResult first = submit(participant, options("한식"));
        RecommendationList matching = completeAndSelect(participant, first);
        RecommendationSubmissionResult second = submit(participant, options("중식"));
        RecommendationList selected = completeAndSelect(participant, second);

        RecommendationSubmissionResult reused = submit(participant, options("한식"));

        assertThat(reused.recommendationId()).isEqualTo(matching.getId());
        assertThat(reused.eventId()).isNull();
        assertThat(reused.dailyGeneration().remaining()).isEqualTo(8);
        RecommendationState state = states.findByUserIdAndRefrigeratorId(participant.userId(), participant.refrigeratorId()).orElseThrow();
        assertThat(state.getCurrentList().getId()).isEqualTo(selected.getId());
        assertThat(state.getLatestList().getId()).isEqualTo(matching.getId());
        assertThat(lists.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(2);
        selections.selectLatest(participant.userId(), participant.refrigeratorId());
        assertThat(states.findById(state.getId()).orElseThrow().getCurrentList().getId()).isEqualTo(matching.getId());
    }

    @Test
    void reusesEquivalentDefaultOptions() throws Exception {
        Fixture participant = ownerFixture("기본값재사용");
        RecommendationList completed = completeAndSelect(participant, submit(participant, RecommendationOptions.empty()));
        RecommendationSubmissionResult reused = submit(participant, RecommendationOptions.empty().effective());
        assertThat(reused.recommendationId()).isEqualTo(completed.getId());
        assertThat(reused.dailyGeneration().remaining()).isEqualTo(9);
        assertThat(preferences.last(participant.userId())).isEqualTo(RecommendationOptions.empty().effective());
        assertThat(redis.getExpire("recipe:preferences:" + participant.userId())).isEqualTo(-1L);
    }

    @Test
    void choosesTheNewestMatchingCompletedHistory() throws Exception {
        Fixture participant = ownerFixture("최신이력");
        RecommendationList first = completeAndSelect(participant, submit(participant, options("한식")));
        RecommendationList newer = new RecommendationList(userRepository.getReferenceById(participant.userId()),
                refrigeratorRepository.getReferenceById(participant.refrigeratorId()), java.util.UUID.randomUUID().toString(), first.getResultJson());
        newer.recordRequestInputs(first.getRequestDate(), first.getInputKey());
        lists.saveAndFlush(newer);
        assertThat(submit(participant, options("한식")).recommendationId()).isEqualTo(newer.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void doesNotReuseYesterdayRequestEvenWhenItCompletedToday(boolean completedToday) throws Exception {
        Fixture participant = ownerFixture("날짜경계");
        clock.set("2026-09-17T14:59:00Z");
        RecommendationSubmissionResult yesterday = submit(participant, RecommendationOptions.empty());
        if (completedToday) { clock.set("2026-09-17T15:00:00Z"); }
        completeAndSelect(participant, yesterday);
        assertThat(lists.findByRequestId(yesterday.requestId()).orElseThrow().getRequestDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 17));
        clock.set("2026-09-17T15:01:00Z");
        RecommendationSubmissionResult today = submit(participant, RecommendationOptions.empty());
        assertThat(today.recommendationId()).isNull();
        assertThat(today.eventId()).isNotNull();
        assertThat(today.requestId()).isNotEqualTo(yesterday.requestId());
        assertThat(today.dailyGeneration().remaining()).isEqualTo(9);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING", "COMPLETED"})
    void acceptsANewDayRequestDespiteYesterdayProgressOrUnselectedResult(String yesterdayStatus) throws Exception {
        Fixture participant = ownerFixture("전날요청");
        clock.set("2026-09-17T14:59:00Z");
        RecommendationSubmissionResult yesterday = submit(participant, options("한식"));
        if ("COMPLETED".equals(yesterdayStatus)) { complete(participant, yesterday); }
        else { jdbcTemplate.update("UPDATE recommendation_states SET generation_status = ? WHERE user_id = ?", yesterdayStatus, participant.userId()); }
        clock.set("2026-09-17T15:01:00Z");
        RecommendationSubmissionResult today = submit(participant, options("한식"));
        assertThat(today.eventId()).isNotNull();
        assertThat(today.requestId()).isNotEqualTo(yesterday.requestId());
        complete(participant, yesterday);
        RecommendationState state = states.findByUserIdAndRefrigeratorId(participant.userId(), participant.refrigeratorId()).orElseThrow();
        assertThat(state.getActiveRequestId()).isEqualTo(today.requestId());
        assertThat(state.getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.QUEUED);
        assertThat(state.getCurrentList()).isNull();
    }

    @Test
    void permitsRetryAfterTodayFailureWithYesterdayResultStillUnselected() throws Exception {
        Fixture participant = ownerFixture("전날결과실패");
        complete(participant, submit(participant, options("한식")));
        clock.set("2026-09-17T15:01:00Z");
        submit(participant, options("한식"));
        jdbcTemplate.update("UPDATE recommendation_states SET generation_status = 'FAILED', active_request_id = NULL WHERE user_id = ?", participant.userId());
        assertThat(submit(participant, options("한식")).eventId()).isNotNull();
    }

    @Test
    void remembersTheLatestAcceptedOptionsAcrossRefrigeratorsIncludingReuse() throws Exception {
        Fixture participant = ownerFixture("취향기억");
        RecommendationSubmissionResult first = new TransactionTemplate(transactionManager).execute(transaction -> {
            RecommendationSubmissionResult accepted = submit(participant, options("한식"));
            assertThat(preferences.last(participant.userId())).isEqualTo(RecommendationOptions.empty().effective());
            return accepted;
        });
        completeAndSelect(participant, first);
        completeAndSelect(participant, submit(participant, options("중식")));
        submit(participant, options("한식"));
        Fixture sharedOwner = ownerFixture("공유소유자");
        memberships.join(participant.userId(), invitations.issueCode(sharedOwner.userId(), sharedOwner.refrigeratorId()).code());
        assertThat(preferences.last(participant.userId())).isEqualTo(options("한식").effective());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesDefaultsOnRedisLossOrFailureWithoutRecoveringOutbox(boolean redisFailure) {
        Fixture participant = ownerFixture("기존취향");
        assertThat(preferences.last(participant.userId())).isEqualTo(RecommendationOptions.empty().effective());
        submit(participant, options("한식"));
        assertThat(preferences.last(participant.userId())).isEqualTo(options("한식").effective());
        if (redisFailure) { doThrow(new RedisConnectionFailureException("preference unavailable")).when(redis).opsForValue(); }
        else { redis.delete("recipe:preferences:" + participant.userId()); }
        assertThat(preferences.last(participant.userId())).isEqualTo(RecommendationOptions.empty().effective());
        jdbcTemplate.update("UPDATE recommendation_states SET generation_status = 'FAILED', active_request_id = NULL WHERE user_id = ?", participant.userId());
        assertThat(submit(participant, options("중식")).eventId()).isNotNull();
    }

    @Test
    void rollsBackRememberedOptionsWhenOutboxWritingFails() {
        Fixture participant = ownerFixture("취향롤백");
        submit(participant, options("한식"));
        jdbcTemplate.update("UPDATE recommendation_states SET generation_status = 'FAILED', active_request_id = NULL WHERE user_id = ?", participant.userId());
        jdbcTemplate.execute("CREATE TRIGGER reject_reuse_request BEFORE INSERT ON recommendation_outbox "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reuse rollback'");
        try {
            assertThatThrownBy(() -> submit(participant, options("중식"))).hasStackTraceContaining("reuse rollback");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_reuse_request");
        }
        assertThat(preferences.last(participant.userId())).isEqualTo(options("한식").effective());
    }

    private RecommendationSubmissionResult submit(Fixture participant, RecommendationOptions options) {
        return submissions.submit(participant.userId(), participant.refrigeratorId(), options);
    }

    private RecommendationList completeAndSelect(Fixture participant, RecommendationSubmissionResult submission) throws Exception {
        complete(participant, submission);
        selections.selectLatest(participant.userId(), participant.refrigeratorId());
        return lists.findByRequestId(submission.requestId()).orElseThrow();
    }

    private void complete(Fixture participant, RecommendationSubmissionResult submission) throws Exception {
        AiRecipeRecommendationRequest request = outbox.request(submission.eventId());
        ObjectNode response = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/recipe-ai/wiki-completed-response.json"));
        response.put("requestId", request.requestId()).put("userId", request.userId()).put("refrigeratorId", request.refrigeratorId());
        ((ObjectNode) response.get("result")).put("userId", request.userId()).put("refrigeratorId", request.refrigeratorId())
                .put("inventoryFingerprint", request.inventoryFingerprint()).put("recommendationMode", request.effectiveMode().name());
        AiRecipeJobResponse completed = mapper.treeToValue(response, AiRecipeJobResponse.class);
        completed.validateForRequest(request);
        completions.apply(completed);
    }

    private RecommendationOptions options(String tag) {
        return RecommendationOptions.fromInput(null, List.of(tag), null, null, null, null, null, null);
    }
}
