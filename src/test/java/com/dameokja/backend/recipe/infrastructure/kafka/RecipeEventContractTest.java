package com.dameokja.backend.recipe.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RecipeEventContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void createsTheRequestEnvelopeWithoutChangingInventoryOrOptions() throws IOException {
        EventEnvelope<RecommendationRequestedPayload> stored = mapper.readValue(fixture("requested").toString(), new TypeReference<>() {});
        AiRecipeRecommendationRequest request = stored.payload().toRequest();
        EventEnvelope<RecommendationRequestedPayload> created = EventEnvelope.create(RecommendationRequestedPayload.from(request, Instant.parse("2026-11-03T03:00:00Z")));
        assertThat(created.payload()).isEqualTo(stored.payload());
        assertThat(created.messageId()).isNotEqualTo(stored.messageId());
        assertThat(request.inventory().getFirst().quantity()).isEqualTo(3);
        assertThat(request.preferenceTags()).containsExactly("매운");
        assertThat(request.strategies()).containsExactly(AiRecipeRecommendationRequest.Strategy.USE_EXPIRING, AiRecipeRecommendationRequest.Strategy.SIMPLE_COOKING);
        assertThat(mapper.valueToTree(created).path("payload").has("eventType")).isFalse();
        RecipeEventValidation.validateKey(stored.payload().userId(), "42");
        assertThatThrownBy(() -> RecipeEventValidation.validateKey(stored.payload().userId(), "43")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "failed"})
    void readsAiTerminalEventsAndKeepsTheirMessageIdentityOnRoundTrip(String name) throws IOException {
        EventEnvelope<RecommendationCompletedPayload> event = mapper.readValue(fixture(name).toString(), new TypeReference<>() {});
        AiRecipeJobResponse response = event.payload().toResponse();
        assertThat(response.requestId()).isEqualTo(fixture("requested").path("payload").path("requestId").asText());
        assertThat(response.status().name()).isEqualTo(name.equals("completed") ? "COMPLETED" : "FAILED");
        if (response.result() != null) {
            assertThat(response.result().recommendations().getFirst().inventoryUsage().getFirst().usedAmount()).isEqualByComparingTo("1");
            assertThat(response.result().preferenceTags()).containsExactly("매운");
        } else {
            assertThat(response.error().retryable()).isTrue();
        }
        String stored = mapper.writeValueAsString(event);
        assertThat(mapper.readValue(stored, new TypeReference<EventEnvelope<RecommendationCompletedPayload>>() {})).isEqualTo(event);
        assertThat(mapper.readTree(stored).path("payload").has("eventType")).isFalse();
        RecipeEventValidation.validateKey(event.payload().userId(), "42");
        assertThatThrownBy(() -> RecipeEventValidation.validateKey(event.payload().userId(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectsCookingToTheOriginalRequestWithoutDuplicatingTheRecommendationList() throws IOException {
        EventEnvelope<CookingAppliedPayload> event = mapper.readValue(fixture("cookingApplied").toString(), new TypeReference<>() {});
        assertThat(event.payload().recommendationId()).isEqualTo("123");
        assertThat(event.payload().requestId()).isEqualTo(fixture("completed").path("payload").path("requestId").asText());
        assertThat(event.payload().recipeId()).isEqualTo("MANUAL:1");
        assertThat(event.payload().consumptions().getFirst().usedAmount()).isEqualByComparingTo("1");
        assertThat(mapper.valueToTree(event).path("payload").has("recommendations")).isFalse();
        assertThat(mapper.readValue(mapper.writeValueAsString(event), new TypeReference<EventEnvelope<CookingAppliedPayload>>() {})).isEqualTo(event);
        RecipeEventValidation.validateKey(event.payload().userId(), "42");
        assertThatThrownBy(() -> RecipeEventValidation.validateKey(event.payload().userId(), "17")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "userId", "refrigeratorId", "result", "completedAt"})
    void rejectsNonterminalOrInconsistentCompletions(String field) throws IOException {
        tools.jackson.databind.node.ObjectNode event = (tools.jackson.databind.node.ObjectNode) fixture("completed");
        tools.jackson.databind.node.ObjectNode payload = (tools.jackson.databind.node.ObjectNode) event.path("payload");
        if (field.equals("status")) { payload.put(field, "PROCESSING"); }
        else if (field.equals("userId") || field.equals("refrigeratorId")) { payload.put(field, 43); }
        else { payload.putNull(field); }
        assertThatThrownBy(() -> mapper.readValue(event.toString(), new TypeReference<EventEnvelope<RecommendationCompletedPayload>>() {})).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageId", "occurredAt", "userId", "refrigeratorId"})
    void rejectsInvalidMessageIdentityTimeOrActor(String field) throws IOException {
        tools.jackson.databind.node.ObjectNode event = (tools.jackson.databind.node.ObjectNode) fixture("requested");
        if (field.equals("messageId")) { event.put(field, "not-a-uuid"); }
        else if (field.equals("occurredAt")) { ((tools.jackson.databind.node.ObjectNode) event.path("payload")).put(field, "2026-11-03T03:00:00"); }
        else { ((tools.jackson.databind.node.ObjectNode) event.path("payload")).put(field, 0); }
        assertThatThrownBy(() -> mapper.readValue(event.toString(), new TypeReference<EventEnvelope<RecommendationRequestedPayload>>() {})).isInstanceOf(RuntimeException.class);
    }

    private JsonNode fixture(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/recipe-kafka/events-v1.json")) {
            return mapper.readTree(stream).path(name);
        }
    }
}
