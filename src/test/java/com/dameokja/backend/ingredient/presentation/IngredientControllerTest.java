package com.dameokja.backend.ingredient.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import com.dameokja.backend.ingredient.presentation.response.IngredientListResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientResponse;
import com.dameokja.backend.ingredient.presentation.request.IngredientUpdateRequest;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
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
        IngredientListResult result = new IngredientListResult(List.of(ingredient), businessDate, 30L, 30L, (short) 100, "next");
        when(ingredientListService.getList(2L, 10L, IngredientSortType.EXPIRATION_ASC, null, null, 10)).thenReturn(result);
        IngredientController controller = new IngredientController(
                ingredientCreateService, ingredientDetailService, ingredientUpdateService, ingredientExpireService,
                ingredientListService);

        ResponseEntity<SuccessResponse<IngredientListResponse>> response = controller.getList(2L, 10L, null, null, null, null);

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

    @Test
    void returnsDetailWithStrongEtag() {
        Ingredient ingredient = ingredient();
        LocalDate businessDate = LocalDate.of(2026, 9, 16);
        when(ingredientDetailService.getDetail(2L, 1L))
                .thenReturn(new IngredientDetailResult(ingredient, businessDate));
        IngredientController controller = new IngredientController(
                ingredientCreateService, ingredientDetailService, ingredientUpdateService, ingredientExpireService,
                ingredientListService);

        ResponseEntity<SuccessResponse<IngredientResponse>> response =
                controller.getDetail(2L, 1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo(IngredientEtag.of(ingredient));
        assertThat(response.getBody().code()).isEqualTo("INGREDIENT-200-003");
        assertThat(response.getBody().data().status()).isEqualTo(IngredientStatus.EXPIRED);
        assertThat(response.getBody().data().daysUntilExpiration()).isEqualTo(-1);
    }

    @Test
    void returnsUpdatedDetailWithNewStrongEtag() {
        Ingredient ingredient = ingredient();
        IngredientUpdateRequest request = new IngredientUpdateRequest();
        request.setWeightValue(new BigDecimal("250"));
        LocalDate businessDate = LocalDate.of(2026, 9, 16);
        when(ingredientUpdateService.update(2L, 1L, "\"before\"", request.toFields()))
                .thenReturn(new IngredientUpdateResult(ingredient, businessDate));
        IngredientController controller = new IngredientController(
                ingredientCreateService, ingredientDetailService, ingredientUpdateService, ingredientExpireService,
                ingredientListService);

        ResponseEntity<SuccessResponse<IngredientResponse>> response =
                controller.update(2L, 1L, "\"before\"", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo(IngredientEtag.of(ingredient));
        assertThat(response.getBody().code()).isEqualTo("INGREDIENT-200-004");
        assertThat(response.getBody().data().registrationSource())
                .isEqualTo(RegistrationSource.RECEIPT);
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
