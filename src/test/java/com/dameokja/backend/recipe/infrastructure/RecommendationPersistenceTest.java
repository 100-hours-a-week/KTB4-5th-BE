package com.dameokja.backend.recipe.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class RecommendationPersistenceTest extends MySqlJpaTest {
    private static final String RESULT_JSON = """
            {"recommendations":[{"recipeId":"RDA:1","name":"두부국","targetServings":2,
            "ingredients":[{"ingredientNo":1,"name":"두부"}],"steps":[{"stepNo":1,"description":"끓인다"}],
            "inventoryUsage":[{"ingredientId":"7","allocations":[{"ingredientNo":1,"usedAmount":100}]}]}]}
            """;
    @Autowired RecommendationListRepository recommendationListRepository;
    @Autowired RecommendationStateRepository recommendationStateRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    private User user;
    private Refrigerator refrigerator;

    @BeforeEach
    void prepareParticipant() {
        user = new User("추천회원", "default.png");
        refrigerator = new Refrigerator("개인냉장고", "2026-10");
        entityManager.persist(user);
        entityManager.persist(refrigerator);
    }

    @Test
    void storesFullResultAsJsonObjectAndReloadsOwnership() {
        RecommendationList saved = saveList();
        flushAndClear();
        RecommendationList found = recommendationListRepository.findByRequestId(saved.getRequestId()).orElseThrow();
        JsonMapper jsonMapper = JsonMapper.builder().build();
        assertThat(jsonMapper.readTree(found.getResultJson())).isEqualTo(jsonMapper.readTree(RESULT_JSON));
        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getRefrigerator().getId()).isEqualTo(refrigerator.getId());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_TYPE(result_json) FROM recommendation_lists", String.class)).isEqualTo("OBJECT");
    }

    @Test
    void retainsSeparateStatesForEachParticipantAndRefrigerator() {
        User otherUser = new User("다른회원", "default.png");
        Refrigerator sharedRefrigerator = new Refrigerator("공유냉장고", "2026-10");
        entityManager.persist(otherUser);
        entityManager.persist(sharedRefrigerator);
        recommendationStateRepository.save(new RecommendationState(user, refrigerator));
        recommendationStateRepository.save(new RecommendationState(user, sharedRefrigerator));
        recommendationStateRepository.save(new RecommendationState(otherUser, sharedRefrigerator));
        flushAndClear();
        RecommendationState personal = recommendationStateRepository.findByUserIdAndRefrigeratorId(user.getId(), refrigerator.getId()).orElseThrow();
        assertThat(personal.getCurrentList()).isNull();
        assertThat(personal.getLatestList()).isNull();
        assertThat(personal.getGenerationStatus()).isNull();
        assertThat(recommendationStateRepository.findByUserIdAndRefrigeratorId(user.getId(), sharedRefrigerator.getId())).isPresent();
        assertThat(recommendationStateRepository.findByUserIdAndRefrigeratorId(otherUser.getId(), sharedRefrigerator.getId())).isPresent();
    }

    @Test
    void referencesEachResultOnceWhileKeepingCurrentAndLatestIndependent() {
        RecommendationList previous = saveList();
        RecommendationList latest = saveList();
        RecommendationState state = recommendationStateRepository.saveAndFlush(new RecommendationState(user, refrigerator));
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ? WHERE recommendation_state_id = ?",
                previous.getId(), latest.getId(), state.getId());
        entityManager.clear();
        RecommendationState found = recommendationStateRepository.findById(state.getId()).orElseThrow();
        assertThat(found.getCurrentList().getId()).isEqualTo(previous.getId());
        assertThat(found.getLatestList().getId()).isEqualTo(latest.getId());
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = latest_list_id WHERE recommendation_state_id = ?", state.getId());
        entityManager.clear();
        RecommendationState activated = recommendationStateRepository.findById(state.getId()).orElseThrow();
        assertThat(activated.getCurrentList().getId()).isEqualTo(activated.getLatestList().getId());
        assertThat(recommendationListRepository.count()).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateParticipantState() {
        recommendationStateRepository.saveAndFlush(new RecommendationState(user, refrigerator));
        assertThatThrownBy(() -> recommendationStateRepository.saveAndFlush(new RecommendationState(user, refrigerator)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reloadsGenerationProgress() {
        RecommendationState state = recommendationStateRepository.saveAndFlush(new RecommendationState(user, refrigerator));
        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                UPDATE recommendation_states SET active_request_id = ?, generation_status = 'PROCESSING',
                generation_trigger = 'USER' WHERE recommendation_state_id = ?
                """, requestId, state.getId());
        entityManager.clear();
        RecommendationState found = recommendationStateRepository.findById(state.getId()).orElseThrow();
        assertThat(found.getActiveRequestId()).isEqualTo(requestId);
        assertThat(found.getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.PROCESSING);
        assertThat(found.getGenerationTrigger()).isEqualTo(RecommendationState.GenerationTrigger.USER);
    }

    @Test
    void rejectsDuplicateRequestAcrossRefrigerators() {
        RecommendationList saved = saveList();
        Refrigerator otherRefrigerator = new Refrigerator("다른냉장고", "2026-10");
        entityManager.persist(otherRefrigerator);
        assertThatThrownBy(() -> recommendationListRepository.saveAndFlush(
                new RecommendationList(user, otherRefrigerator, saved.getRequestId(), RESULT_JSON)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private RecommendationList saveList() {
        return recommendationListRepository.saveAndFlush(new RecommendationList(user, refrigerator, UUID.randomUUID().toString(), RESULT_JSON));
    }
}
