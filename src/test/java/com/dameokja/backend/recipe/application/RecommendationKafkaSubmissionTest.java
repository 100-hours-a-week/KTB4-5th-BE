package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.infrastructure.RecommendationJobStore;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeSubmitResponse;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = "recommendation.kafka.submission-enabled=true")
class RecommendationKafkaSubmissionTest extends ServiceIntegrationTest {
    @Autowired private RecommendationSubmissionService recommendationSubmissionService;
    @Autowired private RecommendationOutboxService recommendationOutboxService;
    @Autowired private RecommendationOutboxRepository recommendationOutboxRepository;
    @Autowired private RecommendationOutboxQueue recommendationOutboxQueue;
    @Autowired private RecommendationCallbackService recommendationCallbackService;
    @Autowired private RecommendationJobStore recommendationJobStore;
    @Autowired private JsonMapper jsonMapper;

    private Fixture refrigeratorOwner;
    private RecommendationSubmissionResult kafkaSubmission;

    @BeforeEach
    void submitRecommendation() {
        refrigeratorOwner = ownerFixture("카프카추천회원");
        kafkaSubmission = recommendationSubmissionService.submit(refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId(), null);
    }

    @Test
    void savesTheSameRequestInTheKafkaEnvelopeAndLeasesItOnlyForKafka() {
        RecommendationOutbox kafkaOutbox = recommendationOutboxRepository.findByEventId(kafkaSubmission.eventId()).orElseThrow();
        assertThat(recommendationOutboxService.batch(0, 10)).isEmpty();
        RecommendationOutboxQueue.Lease outboxLease = recommendationOutboxQueue.leaseBatch(10, Duration.ofSeconds(60)).getFirst();
        assertThat(outboxLease.id()).isEqualTo(kafkaSubmission.sequence());
        assertThat(outboxLease.topic()).isEqualTo("local.dameokja.recommendation.requested.v1");
        assertThat(outboxLease.key()).isEqualTo(refrigeratorOwner.userId().toString());

        JsonNode envelope = jsonMapper.readTree(outboxLease.body());
        assertThat(envelope.path("messageId").asString()).isEqualTo(kafkaSubmission.eventId());
        ObjectNode payload = (ObjectNode) envelope.path("payload");
        assertThat(payload.remove("occurredAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(payload).isEqualTo(jsonMapper.readTree(kafkaOutbox.getPayload()));
    }

    @Test
    void existingHttpWorkCanCompleteWhenTheSameRequestAlsoHasAKafkaRow() {
        String httpEventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO recommendation_outbox (event_id, refrigerator_id, request_id, event_type, payload, status, created_at, updated_at)
                SELECT ?, refrigerator_id, request_id, event_type, payload, status, created_at, updated_at FROM recommendation_outbox WHERE event_id = ?
                """, httpEventId, kafkaSubmission.eventId());

        assertThat(recommendationOutboxService.batch(0, 10)).extracting(RecommendationOutboxService.Event::eventId).containsExactly(httpEventId);
        OffsetDateTime currentTime = clock.instant().atOffset(ZoneOffset.UTC);
        AiRecipeSubmitResponse httpAcceptance = new AiRecipeSubmitResponse("http-job", kafkaSubmission.requestId(), AiRecipeJobResponse.Status.QUEUED, currentTime);
        recommendationJobStore.accept(httpEventId, httpAcceptance);
        AiRecipeJobResponse httpCompletion = new AiRecipeJobResponse(httpAcceptance.jobId(), httpAcceptance.requestId(), refrigeratorOwner.userId(),
                refrigeratorOwner.refrigeratorId(), AiRecipeJobResponse.Status.FAILED, currentTime, currentTime, currentTime, null,
                new AiRecipeJobResponse.Error("RECIPE_TIMEOUT", "추천 시간 초과", true));

        recommendationCallbackService.receive(httpCompletion);

        assertThat(recommendationJobStore.find(httpEventId).orElseThrow().view()).isEqualTo(httpCompletion);
        assertThat(recommendationJobStore.find(kafkaSubmission.eventId())).isEmpty();
    }
}
