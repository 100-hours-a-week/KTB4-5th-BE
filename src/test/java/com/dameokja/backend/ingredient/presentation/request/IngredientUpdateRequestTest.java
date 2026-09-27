package com.dameokja.backend.ingredient.presentation.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class IngredientUpdateRequestTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void distinguishesOmittedMeasurementFromExplicitNull() throws Exception {
        IngredientUpdateRequest request = objectMapper.readValue(
                "{\"weightValue\":null}", IngredientUpdateRequest.class);

        IngredientUpdateFields fields = request.toFields();

        assertThat(fields.quantity().provided()).isFalse();
        assertThat(fields.weightValue().provided()).isTrue();
        assertThat(fields.weightValue().value()).isNull();
    }

    @Test
    void rejectsServerManagedOrImmutableFields() throws Exception {
        IngredientUpdateRequest request = objectMapper.readValue(
                "{\"registrationSource\":\"DIRECT\"}", IngredientUpdateRequest.class);

        assertThatThrownBy(request::toFields).isInstanceOf(CustomException.class);
    }
}
