package com.dameokja.backend.ingredient.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.AuthPrincipal;
import com.dameokja.backend.ingredient.application.create.IngredientCreateCommand;
import com.dameokja.backend.ingredient.application.create.IngredientCreateService;
import com.dameokja.backend.ingredient.application.update.IngredientEtag;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields.UpdateField;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateService;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.ingredient.presentation.IngredientController;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IngredientDetailOwnerIntegrationTest extends ServiceIntegrationTest {
    @Autowired IngredientCreateService creates;
    @Autowired IngredientUpdateService updates;
    @Autowired IngredientController controller;

    @Test
    void returnsOriginalRegistrantEvenWhenAnotherMemberReadsAndEditsStock() throws Exception {
        Fixture owner = ownerFixture("owner");
        Ingredient stock = membersStock(owner);

        detail(owner.userId(), stock.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerNickname").value("member"));

        IngredientUpdateFields fields = new IngredientUpdateFields(UpdateField.provided("두유"),
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted());
        Ingredient updated = updates.update(owner.userId(), stock.getId(), IngredientEtag.of(stock), fields).ingredient();

        detail(owner.userId(), stock.getId())
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", IngredientEtag.of(updated)))
                .andExpect(jsonPath("$.code").value("INGREDIENT-200-003"))
                .andExpect(jsonPath("$.data.name").value("두유"))
                .andExpect(jsonPath("$.data.ownerNickname").value("member"));
    }

    @Test
    void rejectsNonMemberWithoutExposingRegistrantNickname() throws Exception {
        Fixture owner = ownerFixture("owner");
        Ingredient stock = membersStock(owner);
        Fixture outsider = ownerFixture("outsider");

        detail(outsider.userId(), stock.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REFRIGERATOR-403-001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private Ingredient membersStock(Fixture owner) {
        User registrant = user("member");
        join(registrant, refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow(), true, false);
        IngredientCreateCommand command = new IngredientCreateCommand("우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 2, null, WeightUnit.NONE,
                LocalDate.of(2026, 9, 25), RegistrationSource.DIRECT);
        return creates.create(registrant.getId(), owner.refrigeratorId(), List.of(command)).items().getFirst().ingredient();
    }

    private ResultActions detail(Long userId, Long ingredientId) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new AuthPrincipal(userId), null));
        try {
            return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build()
                    .perform(get("/api/v1/ingredients/{ingredientId}", ingredientId));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
