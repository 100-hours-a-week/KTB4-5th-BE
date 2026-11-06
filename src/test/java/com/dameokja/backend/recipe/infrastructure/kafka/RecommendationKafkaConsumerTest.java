package com.dameokja.backend.recipe.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.recipe.application.RecommendationOutboxService;
import com.dameokja.backend.recipe.application.RecommendationSubmissionResult;
import com.dameokja.backend.recipe.application.RecommendationSubmissionService;
import com.dameokja.backend.recipe.domain.RecommendationState.GenerationStatus;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.support.ServiceIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = "recommendation.kafka.completion-enabled=true")
class RecommendationKafkaConsumerTest extends ServiceIntegrationTest {
    @Autowired private RecommendationKafkaConsumer consumer;
    @Autowired private RecommendationSubmissionService submissions;
    @Autowired private RecommendationOutboxService outbox;
    @Autowired private RecommendationListRepository lists;
    @Autowired private RecommendationStateRepository states;
    @Autowired private JsonMapper mapper;
    private Fixture user;
    private RecommendationSubmissionResult submission;

    @BeforeEach
    void accept() {
        user = ownerFixture("완료수신회원");
        submission = submissions.submit(user.userId(), user.refrigeratorId(), null);
        jdbcTemplate.update("UPDATE recommendation_outbox SET message_body = JSON_OBJECT('messageId', event_id, 'payload', payload) WHERE event_id = ?", submission.eventId());
    }

    @Test
    void redeliveredCompletionStoresOneResultBeforeOutboxAck() throws Exception {
        ConsumerRecord<String, EventEnvelope<RecommendationCompletedPayload>> record = record(payload(false));
        consumer.consume(record);
        consumer.consume(record);
        assertThat(lists.count()).isEqualTo(1);
        assertThat(states.findByUserIdAndRefrigeratorId(user.userId(), user.refrigeratorId()).orElseThrow().getGenerationStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM recommendation_outbox", String.class)).isEqualTo("PENDING");
    }

    @Test
    void failedCompletionClearsTheActiveRequestWithoutCreatingAResult() throws Exception {
        consumer.consume(record(payload(true)));
        assertThat(states.findByUserIdAndRefrigeratorId(user.userId(), user.refrigeratorId()).orElseThrow().getGenerationStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(states.findByUserIdAndRefrigeratorId(user.userId(), user.refrigeratorId()).orElseThrow().getActiveRequestId()).isNull();
        assertThat(lists.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "requestId", "userId", "refrigeratorId", "inventoryFingerprint", "recommendationMode"})
    void rejectsEventsThatDoNotMatchAnAcceptedKafkaRequest(String field) throws Exception {
        ObjectNode body = mapper.valueToTree(payload(false));
        if (field.equals("requestId")) { body.put(field, "unknown-request"); }
        if (field.equals("userId") || field.equals("refrigeratorId")) {
            long otherId = body.get(field).longValue() + 1;
            body.put(field, otherId);
            ((ObjectNode) body.get("result")).put(field, otherId);
        }
        if (field.equals("inventoryFingerprint")) { ((ObjectNode) body.get("result")).put(field, "different"); }
        if (field.equals("recommendationMode")) { ((ObjectNode) body.get("result")).put(field, "INCLUDE_EXPIRED"); }
        RecommendationCompletedPayload payload = mapper.treeToValue(body, RecommendationCompletedPayload.class);
        ConsumerRecord<String, EventEnvelope<RecommendationCompletedPayload>> record = new ConsumerRecord<>("test.completed", 0, 0L,
                field.equals("key") ? "other" : payload.userId().toString(), EventEnvelope.create(payload));
        assertThatThrownBy(() -> consumer.consume(record)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lists.count()).isZero();
        assertThat(states.findByUserIdAndRefrigeratorId(user.userId(), user.refrigeratorId()).orElseThrow().getGenerationStatus()).isEqualTo(GenerationStatus.QUEUED);
    }

    @Test
    void rejectsCompletionForAnExistingHttpRequest() throws Exception {
        jdbcTemplate.update("UPDATE recommendation_outbox SET message_body = NULL WHERE event_id = ?", submission.eventId());
        ConsumerRecord<String, EventEnvelope<RecommendationCompletedPayload>> record = record(payload(false));
        assertThatThrownBy(() -> consumer.consume(record)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lists.count()).isZero();
        assertThat(states.findByUserIdAndRefrigeratorId(user.userId(), user.refrigeratorId()).orElseThrow().getActiveRequestId()).isEqualTo(submission.requestId());
    }

    @Test
    void databaseFailureRollsBackResultAndPropagatesUntilRedeliverySucceeds() throws Exception {
        ConsumerRecord<String, EventEnvelope<RecommendationCompletedPayload>> record = record(payload(false));
        jdbcTemplate.execute("CREATE TRIGGER reject_kafka_completion BEFORE UPDATE ON recommendation_states "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'completion rejected'");
        try {
            assertThatThrownBy(() -> consumer.consume(record)).isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_kafka_completion");
        }
        assertThat(lists.count()).isZero();
        assertThat(states.findByUserIdAndRefrigeratorId(user.userId(), user.refrigeratorId()).orElseThrow().getActiveRequestId()).isEqualTo(submission.requestId());
        consumer.consume(record);
        assertThat(lists.count()).isEqualTo(1);
    }

    private ConsumerRecord<String, EventEnvelope<RecommendationCompletedPayload>> record(RecommendationCompletedPayload payload) {
        return new ConsumerRecord<>("test.completed", 0, 0L, payload.userId().toString(), EventEnvelope.create(payload));
    }

    private RecommendationCompletedPayload payload(boolean failed) throws Exception {
        AiRecipeRecommendationRequest request = outbox.request(submission.eventId());
        ObjectNode body = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/recipe-ai/wiki-completed-response.json"));
        body.put("requestId", request.requestId()).put("userId", request.userId()).put("refrigeratorId", request.refrigeratorId()).put("occurredAt", clock.instant().toString());
        ((ObjectNode) body.get("result")).put("userId", request.userId()).put("refrigeratorId", request.refrigeratorId()).put("inventoryFingerprint", request.inventoryFingerprint());
        if (failed) {
            body.put("status", "FAILED").putNull("result");
            body.putObject("error").put("code", "RECIPE_TIMEOUT").put("message", "시간 초과").put("retryable", true);
        }
        return mapper.treeToValue(body, RecommendationCompletedPayload.class);
    }
}
