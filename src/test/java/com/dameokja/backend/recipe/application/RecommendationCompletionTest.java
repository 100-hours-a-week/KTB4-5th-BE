package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationJobStore;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse.Status;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeSubmitResponse;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.skyscreamer.jsonassert.JSONAssert;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RecommendationCompletionTest extends ServiceIntegrationTest {
    @Autowired private RecommendationSubmissionService recommendationSubmissionService;
    @Autowired private RecommendationExecutionService recommendationExecutionService;
    @Autowired private RecommendationCallbackService recommendationCallbackService;
    @Autowired private RecommendationOutboxService recommendationOutboxService;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private RecommendationJobStore recommendationJobStore;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private InvitationService invitationService;
    @Autowired private MembershipService membershipService;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @MockitoBean private AiRecipeClient aiRecipeClient;

    @Test
    void callbackStoresOneSnapshotWithoutSwitchingTheSelectedList() throws Exception {
        Fixture refrigeratorParticipant = ownerFixture("완료회원");
        RecommendationSubmissionResult submissionResult = accept(refrigeratorParticipant);
        RecommendationList previousList = selectPreviousList(refrigeratorParticipant);
        AiRecipeJobResponse completedResponse = response(submissionResult, Status.COMPLETED);
        recommendationCallbackService.receive(completedResponse);
        recommendationCallbackService.receive(completedResponse);
        RecommendationState completedState = state(refrigeratorParticipant);
        assertThat(completedState.getCurrentList().getId()).isEqualTo(previousList.getId());
        assertThat(completedState.getLatestList().getId()).isNotEqualTo(previousList.getId());
        assertThat(completedState.getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.COMPLETED);
        assertThat(completedState.getActiveRequestId()).isNull();
        RecommendationList storedList = recommendationListRepository.findByRequestId(submissionResult.requestId()).orElseThrow();
        JSONAssert.assertEquals(jsonMapper.writeValueAsString(completedResponse.result()), storedList.getResultJson(), true);
        assertThat(recommendationListRepository.count()).isEqualTo(2);
        assertThatThrownBy(() -> recommendationSubmissionService.submit(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), null))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getExceptionCode()).isEqualTo(RecommendationExceptionCode.UNSELECTED_RESULT));
    }

    @Test
    void pollingUpdatesProgressAndStoresTheInitialResultAsUnselected() throws Exception {
        Fixture refrigeratorParticipant = ownerFixture("조회회원");
        RecommendationSubmissionResult submissionResult = accept(refrigeratorParticipant);
        when(aiRecipeClient.get(any(), any())).thenReturn(response(submissionResult, Status.PROCESSING), response(submissionResult, Status.COMPLETED));
        recommendationExecutionService.refresh(submissionResult.eventId());
        assertThat(state(refrigeratorParticipant).getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.PROCESSING);
        recommendationExecutionService.refresh(submissionResult.eventId());
        recommendationExecutionService.refresh(submissionResult.eventId());
        assertThat(state(refrigeratorParticipant).getCurrentList()).isNull();
        assertThat(state(refrigeratorParticipant).getLatestList()).isNotNull();
        assertThat(recommendationListRepository.count()).isEqualTo(1);
        verify(aiRecipeClient, times(2)).get(any(), any());
    }

    @Test
    void failureAllowsANewRequestAndLateOldResultsCannotChangeIt() throws Exception {
        Fixture refrigeratorParticipant = ownerFixture("실패회원");
        RecommendationSubmissionResult failedSubmission = accept(refrigeratorParticipant);
        RecommendationList previousList = selectPreviousList(refrigeratorParticipant);
        recommendationCallbackService.receive(response(failedSubmission, Status.FAILED));
        assertThat(state(refrigeratorParticipant).getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.FAILED);
        assertThat(state(refrigeratorParticipant).getActiveRequestId()).isNull();
        assertThat(state(refrigeratorParticipant).getCurrentList().getId()).isEqualTo(previousList.getId());
        assertThat(state(refrigeratorParticipant).getLatestList().getId()).isEqualTo(previousList.getId());
        RecommendationSubmissionResult nextSubmission = accept(refrigeratorParticipant);
        recommendationCallbackService.receive(response(failedSubmission, Status.COMPLETED));
        assertThat(state(refrigeratorParticipant).getActiveRequestId()).isEqualTo(nextSubmission.requestId());
        assertThat(state(refrigeratorParticipant).getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.QUEUED);
        assertThat(recommendationListRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retriesDatabaseFailureEvenWhenRedisAlreadyContainsCompletion(boolean receivedThroughCallback) throws Exception {
        Fixture refrigeratorParticipant = ownerFixture("복구회원");
        RecommendationSubmissionResult submissionResult = accept(refrigeratorParticipant);
        AiRecipeJobResponse completedResponse = response(submissionResult, Status.COMPLETED);
        when(aiRecipeClient.get(any(), any())).thenReturn(completedResponse);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_recommendation_completion BEFORE UPDATE ON recommendation_states FOR EACH ROW
                BEGIN
                    IF NEW.generation_status = 'COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'completion rejected'; END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> {
                if (receivedThroughCallback) { recommendationCallbackService.receive(completedResponse); }
                else { recommendationExecutionService.refresh(submissionResult.eventId()); }
            }).isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_recommendation_completion");
        }
        assertThat(recommendationListRepository.count()).isZero();
        assertThat(state(refrigeratorParticipant).getActiveRequestId()).isEqualTo(submissionResult.requestId());
        assertThat(recommendationJobStore.find(submissionResult.eventId()).orElseThrow().terminal()).isTrue();
        recommendationExecutionService.refresh(submissionResult.eventId());
        assertThat(recommendationListRepository.count()).isEqualTo(1);
        assertThat(state(refrigeratorParticipant).getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.COMPLETED);
        verify(aiRecipeClient, times(receivedThroughCallback ? 0 : 1)).get(any(), any());
    }

    @Test
    void concurrentPollingAndCallbackCommitOneResult() throws Exception {
        Fixture refrigeratorParticipant = ownerFixture("동시회원");
        RecommendationSubmissionResult submissionResult = accept(refrigeratorParticipant);
        AiRecipeJobResponse completedResponse = response(submissionResult, Status.COMPLETED);
        when(aiRecipeClient.get(any(), any())).thenReturn(completedResponse);
        assertThat(concurrently(List.of(() -> recommendationExecutionService.refresh(submissionResult.eventId()), () -> {
            recommendationCallbackService.receive(completedResponse);
            return "receivedThroughCallback";
        }))).noneMatch(Throwable.class::isInstance);
        assertThat(recommendationListRepository.count()).isEqualTo(1);
        assertThat(state(refrigeratorParticipant).getLatestList().getId()).isEqualTo(recommendationListRepository.findByRequestId(submissionResult.requestId()).orElseThrow().getId());
    }

    @Test
    void concurrentGenerationRequestRemainsBlockedWhileCompletionCommits() throws Exception {
        Fixture refrigeratorParticipant = ownerFixture("접수경합회원");
        RecommendationSubmissionResult submissionResult = accept(refrigeratorParticipant);
        AiRecipeJobResponse completedResponse = response(submissionResult, Status.COMPLETED);
        List<Object> concurrentResults = concurrently(List.of(() -> {
            recommendationCallbackService.receive(completedResponse);
            return "completed";
        }, () -> recommendationSubmissionService.submit(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), null)));
        assertThat(concurrentResults).contains("completed");
        assertThat(concurrentResults.stream().filter(CustomException.class::isInstance).map(CustomException.class::cast).toList())
                .singleElement().satisfies(exception -> assertThat(exception.getExceptionCode())
                        .isIn(RecommendationExceptionCode.GENERATION_IN_PROGRESS, RecommendationExceptionCode.UNSELECTED_RESULT));
        assertThat(state(refrigeratorParticipant).getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.COMPLETED);
        assertThat(recommendationListRepository.count()).isEqualTo(1);
    }

    @Test
    void leavingASharedRefrigeratorKeepsCompletionInItsOriginalParticipantScope() throws Exception {
        Fixture refrigeratorOwner = ownerFixture("공유회원");
        Fixture refrigeratorParticipant = ownerFixture("이동회원");
        membershipService.join(refrigeratorParticipant.userId(), invitationService.issueCode(refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId()).code());
        RecommendationSubmissionResult sharedSubmission = accept(refrigeratorParticipant.userId(), refrigeratorOwner.refrigeratorId());
        membershipService.leave(refrigeratorParticipant.userId(), refrigeratorOwner.refrigeratorId());
        RecommendationSubmissionResult personalSubmission = accept(refrigeratorParticipant);
        recommendationCallbackService.receive(response(sharedSubmission, Status.COMPLETED));
        RecommendationState sharedState = recommendationStateRepository.findByUserIdAndRefrigeratorId(refrigeratorParticipant.userId(), refrigeratorOwner.refrigeratorId()).orElseThrow();
        assertThat(sharedState.getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.COMPLETED);
        assertThat(state(refrigeratorParticipant).getActiveRequestId()).isEqualTo(personalSubmission.requestId());
        assertThat(state(refrigeratorParticipant).getLatestList()).isNull();
        assertThat(recommendationStateRepository.findByUserIdAndRefrigeratorId(refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId())).isEmpty();
    }

    private RecommendationSubmissionResult accept(Fixture refrigeratorParticipant) {
        return accept(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId());
    }

    private RecommendationSubmissionResult accept(Long userId, Long refrigeratorId) {
        RecommendationSubmissionResult submissionResult = recommendationSubmissionService.submit(userId, refrigeratorId, null);
        when(aiRecipeClient.submit(any())).thenReturn(new AiRecipeSubmitResponse("job-" + submissionResult.eventId(), submissionResult.requestId(), Status.QUEUED,
                OffsetDateTime.parse("2026-10-30T03:00:00Z")));
        recommendationExecutionService.submit(submissionResult.eventId());
        return submissionResult;
    }

    private RecommendationState state(Fixture refrigeratorParticipant) {
        return recommendationStateRepository.findByUserIdAndRefrigeratorId(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId()).orElseThrow();
    }

    private RecommendationList selectPreviousList(Fixture refrigeratorParticipant) {
        RecommendationList previousList = recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()), UUID.randomUUID().toString(), "{}"));
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ? WHERE user_id = ? AND refrigerator_id = ?",
                previousList.getId(), previousList.getId(), refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId());
        return previousList;
    }

    private AiRecipeJobResponse response(RecommendationSubmissionResult submissionResult, Status status) throws Exception {
        AiRecipeRecommendationRequest recommendationRequest = recommendationOutboxService.request(submissionResult.eventId());
        ObjectNode responseBody = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/wiki-completed-response.json"));
        responseBody.put("jobId", "job-" + submissionResult.eventId()).put("requestId", submissionResult.requestId()).put("userId", recommendationRequest.userId())
                .put("refrigeratorId", recommendationRequest.refrigeratorId()).put("status", status.name());
        if (status == Status.COMPLETED) {
            ((ObjectNode) responseBody.get("result")).put("userId", recommendationRequest.userId()).put("refrigeratorId", recommendationRequest.refrigeratorId())
                    .put("inventoryFingerprint", recommendationRequest.inventoryFingerprint());
        } else {
            responseBody.putNull("result");
            if (status == Status.FAILED) { responseBody.putObject("error").put("code", "RECIPE_TIMEOUT").put("message", "시간 초과").put("retryable", true); }
            else { responseBody.putNull("completedAt"); }
        }
        return jsonMapper.treeToValue(responseBody, AiRecipeJobResponse.class);
    }
}
