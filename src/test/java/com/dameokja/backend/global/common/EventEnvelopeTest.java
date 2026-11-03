package com.dameokja.backend.global.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class EventEnvelopeTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void roundTripsDifferentPayloadsWithoutDependingOnRecipeFields() {
        EventEnvelope<String> textEvent = EventEnvelope.create("simple-event");
        EventEnvelope<Integer> numberEvent = EventEnvelope.create(17);
        assertThat(mapper.readValue(mapper.writeValueAsString(textEvent), new TypeReference<EventEnvelope<String>>() {})).isEqualTo(textEvent);
        assertThat(mapper.readValue(mapper.writeValueAsString(numberEvent), new TypeReference<EventEnvelope<Integer>>() {})).isEqualTo(numberEvent);
        assertThat(mapper.readTree(mapper.writeValueAsString(textEvent))).isEqualTo(mapper.readTree(
                "{\"messageId\":\"" + textEvent.messageId() + "\",\"payload\":\"simple-event\"}"));
    }

    @Test
    void rejectsInvalidIdentityAndMissingPayload() {
        assertThatThrownBy(() -> new EventEnvelope<>("invalid", "event")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventEnvelope.create(null)).isInstanceOf(NullPointerException.class);
    }
}
