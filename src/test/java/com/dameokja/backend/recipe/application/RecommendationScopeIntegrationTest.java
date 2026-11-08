package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class RecommendationScopeIntegrationTest extends ServiceIntegrationTest {
    @Autowired private RecommendationSubmissionService recommendationSubmissionService;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @Autowired private InvitationService invitationService;
    @Autowired private MembershipService membershipService;
    @MockitoBean private AiRecipeClient aiRecipeClient;

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING", "COMPLETED"})
    void permitsRequestsAfterLeavingSharedRefrigeratorAndPreservesItsBlockingState(String generationStatus) {
        Fixture sharedOwner = ownerFixture("공유소유자");
        Fixture participant = ownerFixture("이동회원");
        String inviteCode = invitationService.issueCode(sharedOwner.userId(), sharedOwner.refrigeratorId()).code();
        membershipService.join(participant.userId(), inviteCode);
        RecommendationSubmissionResult previous = recommendationSubmissionService.submit(participant.userId(), sharedOwner.refrigeratorId(), null);
        Long latestListId = setSharedGenerationState(participant.userId(), sharedOwner.refrigeratorId(), generationStatus);
        RecommendationExceptionCode expected = generationStatus.equals("COMPLETED")
                ? RecommendationExceptionCode.UNSELECTED_RESULT : RecommendationExceptionCode.GENERATION_IN_PROGRESS;
        assertBlocked(participant.userId(), sharedOwner.refrigeratorId(), expected);

        assertThat(membershipService.leave(participant.userId(), sharedOwner.refrigeratorId())).isEqualTo(participant.refrigeratorId());
        RecommendationSubmissionResult next = recommendationSubmissionService.submit(participant.userId(), participant.refrigeratorId(), null);
        RecommendationState previousState = recommendationStateRepository.findByUserIdAndRefrigeratorId(participant.userId(), sharedOwner.refrigeratorId()).orElseThrow();
        assertThat(previousState.getGenerationStatus().name()).isEqualTo(generationStatus);
        assertThat(previousState.getActiveRequestId()).isEqualTo(generationStatus.equals("COMPLETED") ? null : previous.requestId());
        assertThat(previousState.getCurrentList()).isNull();
        assertThat(previousState.getLatestList() == null ? null : previousState.getLatestList().getId()).isEqualTo(latestListId);
        assertThat(recommendationStateRepository.findByUserIdAndRefrigeratorId(participant.userId(), participant.refrigeratorId()).orElseThrow().getActiveRequestId())
                .isEqualTo(next.requestId());
        assertThat(recommendationStateRepository.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(2);

        membershipService.join(participant.userId(), inviteCode);
        assertBlocked(participant.userId(), sharedOwner.refrigeratorId(), expected);
        verifyNoInteractions(aiRecipeClient);
    }

    private Long setSharedGenerationState(Long userId, Long refrigeratorId, String generationStatus) {
        Long latestListId = null;
        if (generationStatus.equals("COMPLETED")) {
            latestListId = recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(userId),
                    refrigeratorRepository.getReferenceById(refrigeratorId), UUID.randomUUID().toString(), "{}")).getId();
        }
        jdbcTemplate.update("""
                UPDATE recommendation_states SET generation_status = ?, latest_list_id = ?,
                active_request_id = CASE WHEN ? = 'COMPLETED' THEN NULL ELSE active_request_id END
                WHERE user_id = ? AND refrigerator_id = ?
                """, generationStatus, latestListId, generationStatus, userId, refrigeratorId);
        return latestListId;
    }

    private void assertBlocked(Long userId, Long refrigeratorId, RecommendationExceptionCode expected) {
        assertThatThrownBy(() -> recommendationSubmissionService.submit(userId, refrigeratorId, null))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getExceptionCode()).isEqualTo(expected));
    }
}
