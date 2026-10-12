package com.dameokja.backend.ingredient.application.create;

import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReproIngredientCreateIntegrationTest extends ServiceIntegrationTest {
    @Autowired IngredientCreateService ingredientCreateService;

    @Test
    void reproduces500ForAsianGamesBaseballPayload() {
        Fixture fixture = ownerFixture("owner1");
        clock.set("2026-09-27T12:24:37Z");

        IngredientCreateCommand command = new IngredientCreateCommand(
                "왜안되징", IngredientCategory.OTHER, StorageType.FROZEN,
                MeasureType.COUNT, 1, null, WeightUnit.NONE,
                LocalDate.of(2026, 9, 27), RegistrationSource.DIRECT);

        ingredientCreateService.create(fixture.userId(), fixture.refrigeratorId(), List.of(command));
    }
}
