package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.image.application.ProfileImageService;
import com.dameokja.backend.ingredient.application.expire.IngredientExpireService;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRole;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {"spring.config.import=", "push.dispatch.enabled=false",
        "refrigerator.expiration-accounting.enabled=false", "app.user.default-profile-image-key=profiles/default.png"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MypageIntegrationTest extends ServiceIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired IngredientRepository ingredients;
    @Autowired IngredientExpireService expire;
    @Autowired NotificationPreferenceService preferences;
    @Autowired SocialAccountRepository socialAccounts;
    @MockitoBean ProfileImageService images;
    MockMvc mvc;

    @BeforeEach
    void configureMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void returnsProfilePreferencesAndMonthlyCountThenKeepsCountAfterDeletion() throws Exception {
        Fixture fixture = ownerFixture("dave");
        User user = userRepository.findById(fixture.userId()).orElseThrow();
        preferences.createDefaults(user);
        Long ingredientId = expiredIngredient(fixture).getId();
        when(images.readUrl("profiles/original.png")).thenReturn("https://example.com/profile?signature=test");
        read(fixture).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("REFRIGERATOR-200-007"))
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data.userId").value(fixture.userId().toString()))
                .andExpect(jsonPath("$.data.refrigeratorId").value(fixture.refrigeratorId().toString()))
                .andExpect(jsonPath("$.data.nickname").value("dave"))
                .andExpect(jsonPath("$.data.email").value(nullValue()))
                .andExpect(jsonPath("$.data.refrigeratorName").value("dave"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://example.com/profile?signature=test"))
                .andExpect(jsonPath("$.data.memberNum").value(1))
                .andExpect(jsonPath("$.data.ingredientsNum").value(1))
                .andExpect(jsonPath("$.data.expiredIngredientsNum").value(1))
                .andExpect(jsonPath("$.data.notificationPreferences[1].type").value("RECIPE"))
                .andExpect(jsonPath("$.data.notificationPreferences[1].isEnabled").value(false));
        assertThat(refrigeratorRepository.findById(fixture.refrigeratorId()).orElseThrow().getExpiredCountedOn()).isNull();
        expire.expireSelected(fixture.userId(), fixture.refrigeratorId(), List.of(ingredientId));
        read(fixture).andExpect(jsonPath("$.data.ingredientsNum").value(0))
                .andExpect(jsonPath("$.data.expiredIngredientsNum").value(1));
        clock.set("2026-10-01T00:00:00Z");
        read(fixture).andExpect(jsonPath("$.data.expiredIngredientsNum").value(0));
    }

    @Test
    void readsSavedKakaoEmailWithoutExposingAnImageKey() throws Exception {
        Fixture fixture = ownerFixture("kakao");
        User user = userRepository.findById(fixture.userId()).orElseThrow();
        socialAccounts.save(new SocialAccount(user, OAuthProvider.KAKAO, "kakao-1", "dave@example.com"));
        read(fixture).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("dave@example.com"))
                .andExpect(jsonPath("$.data.profileImageKey").doesNotExist());
    }

    @Test
    void unauthenticatedAndForeignRefrigeratorReadsCannotSignImages() throws Exception {
        Fixture fixture = ownerFixture("owner");
        Fixture foreign = ownerFixture("other");
        mvc.perform(get("/api/v1/refrigerators/{id}/mypage", fixture.refrigeratorId()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/refrigerators/{id}/mypage", foreign.refrigeratorId()).cookie(access(fixture)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REFRIGERATOR-403-001"));
        verifyNoInteractions(images);
    }

    @Test
    void missingAndDeletedRefrigeratorsReturnExistingErrors() throws Exception {
        Fixture fixture = ownerFixture("owner");
        mvc.perform(get("/api/v1/refrigerators/{id}/mypage", Long.MAX_VALUE).cookie(access(fixture)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REFRIGERATOR-404-001"));
        jdbcTemplate.update("UPDATE refrigerators SET deleted_at = '2026-09-17 12:00:00' WHERE refrigerator_id = ?", fixture.refrigeratorId());
        read(fixture).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("REFRIGERATOR-410-001"));
        verifyNoInteractions(images);
    }

    private ResultActions read(Fixture fixture) throws Exception {
        return mvc.perform(get("/api/v1/refrigerators/{id}/mypage", fixture.refrigeratorId())
                .cookie(access(fixture)).param("userId", "999"));
    }

    private Cookie access(Fixture fixture) {
        return new Cookie("accessToken", jwt.createAccessToken(fixture.userId(), UserRole.USER));
    }

    private Ingredient expiredIngredient(Fixture fixture) {
        Refrigerator refrigerator = refrigeratorRepository.findById(fixture.refrigeratorId()).orElseThrow();
        IngredientDetails details = new IngredientDetails("달걀", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), LocalDate.of(2026, 9, 16));
        return ingredients.save(new Ingredient(refrigerator, details, RegistrationSource.DIRECT, userRepository.findById(fixture.userId()).orElseThrow()));
    }
}
