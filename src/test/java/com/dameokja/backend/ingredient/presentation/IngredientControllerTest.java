package com.dameokja.backend.ingredient.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.ingredient.application.create.IngredientCreateService;
import com.dameokja.backend.ingredient.application.detail.IngredientDetailResult;
import com.dameokja.backend.ingredient.application.detail.IngredientDetailService;
import com.dameokja.backend.ingredient.application.expire.IngredientExpireService;
import com.dameokja.backend.ingredient.application.list.IngredientListResult;
import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.ingredient.application.update.IngredientEtag;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateService;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateResult;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.domain.IngredientStatus;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.ingredient.presentation.response.IngredientDetailResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientListResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientUpdateResponse;
import com.dameokja.backend.ingredient.presentation.request.IngredientExpireSelectedRequest;
import com.dameokja.backend.ingredient.presentation.request.IngredientUpdateRequest;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IngredientControllerTest {
    @Mock private IngredientCreateService ingredientCreateService;
    @Mock private IngredientDetailService ingredientDetailService;
    @Mock private IngredientUpdateService ingredientUpdateService;
    @Mock private IngredientExpireService ingredientExpireService;
    @Mock private IngredientListService ingredientListService;

    @Test
    void returnsListWithDefaultParametersAndStatusOfBusinessDate() {
        Ingredient ingredient = ingredient();
        LocalDate businessDate = LocalDate.of(2026, 9, 16);
        IngredientListResult result = new IngredientListResult(List.of(ingredient), businessDate, false, 30L, 30L, (short) 100, "next");
        when(ingredientListService.getList(2L, 10L, IngredientSortType.EXPIRATION_ASC, null, null, null, null, 10)).thenReturn(result);
        IngredientController controller = new IngredientController(
                ingredientCreateService, ingredientDetailService, ingredientUpdateService, ingredientExpireService,
                ingredientListService);

        ResponseEntity<SuccessResponse<IngredientListResponse>> response = controller.getList(2L, 10L, null, null, null, null, null, null);

        IngredientListResponse data = response.getBody().data();
        assertThat(response.getBody().code()).isEqualTo("INGREDIENT-200-002");
        assertThat(data.ingredientsNum()).isEqualTo(30L);
        assertThat(data.filteredCount()).isEqualTo(30L);
        assertThat(data.refrigeratorCapacity()).isEqualTo((short) 100);
        assertThat(data.nextCursor()).isEqualTo("next");
        assertThat(data.ingredients()).singleElement().satisfies(item -> {
            assertThat(item.ingredientId()).isEqualTo("1");
            assertThat(item.weightValue()).isEqualByComparingTo("300");
            assertThat(item.status()).isEqualTo(IngredientStatus.EXPIRED);
            assertThat(item.daysUntilExpiration()).isEqualTo(-1);
        });
    }

    @ParameterizedTest
    @CsvSource(value = {"'',", "vegetable,", "UNKNOWN,", "VEGETABLE,FRUIT", "VEGETABLE,VEGETABLE"})
    void rejectsInvalidOrRepeatedCategoryQuery(String first, String second) throws Exception {
        String[] categories = second == null ? new String[] {first} : new String[] {first, second};
        listMvc().perform(get("/api/v1/refrigerators/10/ingredients").param("category", categories))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGREDIENT-400-003"));
        verifyNoInteractions(ingredientListService);
    }

    @ParameterizedTest
    @EnumSource(IngredientCategory.class)
    @NullSource
    void bindsSingleCategoryOrOmission(IngredientCategory category) throws Exception {
        IngredientListResult result = new IngredientListResult(List.of(), LocalDate.of(2026, 9, 23), false, 5L, 0L, (short) 100, null);
        when(ingredientListService.getList(null, 10L, IngredientSortType.EXPIRATION_ASC, null, category, null, null, 10)).thenReturn(result);
        MockHttpServletRequestBuilder request = get("/api/v1/refrigerators/10/ingredients");
        if (category != null) {
            request.param("category", category.name());
        }

        listMvc().perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.ingredientsNum").value(5));
        verify(ingredientListService).getList(null, 10L, IngredientSortType.EXPIRATION_ASC, null, category, null, null, 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"두", " 두 "})
    void rejectsOneCharacterKeyword(String keyword) throws Exception {
        listMvc().perform(get("/api/v1/refrigerators/10/ingredients").param("keyword", keyword))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGREDIENT-400-003"));
        verifyNoInteractions(ingredientListService);
    }

    @ParameterizedTest
    @CsvSource(value = {"'  두부  ',두부", "'',", "'   ',"})
    void bindsNormalizedKeyword(String keyword, String expected) throws Exception {
        IngredientListResult result = new IngredientListResult(List.of(), LocalDate.of(2026, 9, 23), false, 5L, 0L, (short) 100, null);
        when(ingredientListService.getList(null, 10L, IngredientSortType.EXPIRATION_ASC, null, null, expected, null, 10)).thenReturn(result);

        listMvc().perform(get("/api/v1/refrigerators/10/ingredients").param("keyword", keyword)).andExpect(status().isOk());
        verify(ingredientListService).getList(null, 10L, IngredientSortType.EXPIRATION_ASC, null, null, expected, null, 10);
    }

    private MockMvc listMvc() {
        IngredientController controller = new IngredientController(ingredientCreateService, ingredientDetailService,
                ingredientUpdateService, ingredientExpireService, ingredientListService);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @Test
    void returnsDetailWithStrongEtag() {
        Ingredient ingredient = ingredient();
        LocalDate businessDate = LocalDate.of(2026, 9, 16);
        when(ingredientDetailService.getDetail(2L, 1L))
                .thenReturn(new IngredientDetailResult(ingredient, businessDate));
        IngredientController controller = new IngredientController(
                ingredientCreateService, ingredientDetailService, ingredientUpdateService, ingredientExpireService,
                ingredientListService);

        ResponseEntity<SuccessResponse<IngredientDetailResponse>> response =
                controller.getDetail(2L, 1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo(IngredientEtag.of(ingredient));
        assertThat(response.getBody().code()).isEqualTo("INGREDIENT-200-003");
        assertThat(response.getBody().data().ingredient().status()).isEqualTo(IngredientStatus.EXPIRED);
        assertThat(response.getBody().data().ingredient().daysUntilExpiration()).isEqualTo(-1);
    }

    @Test
    void returnsUpdatedDetailWithNewStrongEtag() {
        Ingredient ingredient = ingredient();
        IngredientUpdateRequest request = new IngredientUpdateRequest();
        request.setWeightValue(new BigDecimal("250"));
        LocalDate businessDate = LocalDate.of(2026, 9, 16);
        when(ingredientUpdateService.update(2L, 1L, "\"before\"", request.toFields()))
                .thenReturn(new IngredientUpdateResult(ingredient, businessDate, List.of()));
        IngredientController controller = new IngredientController(
                ingredientCreateService, ingredientDetailService, ingredientUpdateService, ingredientExpireService,
                ingredientListService);

        ResponseEntity<SuccessResponse<IngredientUpdateResponse>> response =
                controller.update(2L, 1L, "\"before\"", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo(IngredientEtag.of(ingredient));
        assertThat(response.getBody().code()).isEqualTo("INGREDIENT-200-004");
        assertThat(response.getBody().data().ingredient().registrationSource())
                .isEqualTo(RegistrationSource.RECEIPT);
        assertThat(response.getBody().data().mergedItems()).isEmpty();
    }

    @Test
    void expiresSelectedIngredientsAndReturnsNoContent() {
        IngredientController controller = new IngredientController(
                ingredientCreateService, ingredientDetailService, ingredientUpdateService, ingredientExpireService,
                ingredientListService);

        ResponseEntity<Void> response = controller.expireSelected(
                2L, 10L, new IngredientExpireSelectedRequest(List.of(1L, 2L)));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(ingredientExpireService).expireSelected(2L, 10L, List.of(1L, 2L));
    }

    private Ingredient ingredient() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        IngredientDetails details = new IngredientDetails(
                "두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.WEIGHT, null, new BigDecimal("300"), WeightUnit.G),
                LocalDate.of(2026, 9, 15));
        Ingredient ingredient = new Ingredient(refrigerator, details, RegistrationSource.RECEIPT);
        ReflectionTestUtils.setField(ingredient, "id", 1L);
        ReflectionTestUtils.setField(ingredient, "createdAt",
                LocalDateTime.of(2026, 9, 1, 0, 0));
        ReflectionTestUtils.setField(ingredient, "updatedAt",
                LocalDateTime.of(2026, 9, 16, 0, 0));
        return ingredient;
    }
}
