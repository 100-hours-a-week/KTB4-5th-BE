package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.ingredient.application.update.IngredientEtag;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.MonthlyExpirationService;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class MonthlyExpirationHttpIntegrationTest extends ServiceIntegrationTest {
    @Autowired MonthlyExpirationService counts;
    @Autowired IngredientRepository ingredients;
    @Autowired RefrigeratorService refrigerators;
    @Autowired WebApplicationContext webContext;
    @Autowired JwtProvider jwt;

    @Test
    void httpResponseUsesAuthenticatedUserAndExistingSpecification() throws Exception {
        Fixture fixture = ownerFixture("owner");
        ingredient(fixture, "달걀", "2026-09-16");
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        Cookie cookie = new Cookie("accessToken", jwt.createAccessToken(fixture.userId(), UserRole.USER));
        mvc.perform(get("/api/v1/refrigerators/{id}/expired", fixture.refrigeratorId()).cookie(cookie).param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INGREDIENT-200-001"))
                .andExpect(jsonPath("$.data.expiredIngredientsNum").value(1));
        mvc.perform(get("/api/v1/refrigerators/{id}/expired", fixture.refrigeratorId()))
                .andExpect(status().isUnauthorized());
        Fixture other = ownerFixture("other");
        mvc.perform(get("/api/v1/refrigerators/{id}/expired", other.refrigeratorId()).cookie(cookie))
                .andExpect(status().isForbidden());
        assertThat(saved(fixture).getExpiredCountedOn()).isNull();
    }

    @Test
    void expirationAtMidnightIsVisibleWithoutBatchOrDeletionAndGetDoesNotWrite() {
        Fixture fixture = ownerFixture("owner");
        Ingredient ingredient = ingredient(fixture, "달걀", "2026-09-17");
        String etag = IngredientEtag.of(ingredient);
        clock.set("2026-09-17T14:59:59Z");
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isZero();
        clock.set("2026-09-17T15:00:00Z");
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isOne();
        assertThat(saved(fixture).getExpiredCountedOn()).isNull();
        assertThat(refrigerators.getActiveRefrigerators(fixture.userId()).getFirst().expiredCount()).isOne();

        counts.synchronize(fixture.refrigeratorId());
        counts.synchronize(fixture.refrigeratorId());
        assertThat(saved(fixture).getExpiredCount()).isOne();
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isOne();
        assertThat(IngredientEtag.of(ingredients.findById(ingredient.getId()).orElseThrow())).isEqualTo(etag);
    }

    private Ingredient ingredient(Fixture fixture, String name, String expiration) {
        IngredientDetails details = new IngredientDetails(name, IngredientCategory.OTHER, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 10, null, WeightUnit.NONE), LocalDate.parse(expiration));
        return ingredients.saveAndFlush(new Ingredient(saved(fixture), details, RegistrationSource.DIRECT));
    }

    private Refrigerator saved(Fixture fixture) {
        return refrigeratorRepository.findById(fixture.refrigeratorId()).orElseThrow();
    }
}
