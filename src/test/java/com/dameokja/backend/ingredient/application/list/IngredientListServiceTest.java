package com.dameokja.backend.ingredient.application.list;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_CURSOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class IngredientListServiceTest {
    private static final Long USER_ID = 2L;
    private static final Long REFRIGERATOR_ID = 10L;
    // 2026-09-23 03:00 UTC = 2026-09-23 12:00 KST
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    @Mock private RefrigeratorAccessService accessService;
    @Mock private IngredientRepository ingredientRepository;
    @Mock private IngredientPageReader ingredientPageReader;
    private final IngredientListCursorCodec cursorCodec = new IngredientListCursorCodec(JsonMapper.builder().build());
    private IngredientListService ingredientListService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T03:00:00Z"), ZoneOffset.UTC);
        ingredientListService = new IngredientListService(
                accessService, ingredientRepository, ingredientPageReader, cursorCodec, clock);
        when(accessService.validateReadAccess(USER_ID, REFRIGERATOR_ID)).thenReturn(new Refrigerator("냉장고", "2026-09"));
    }

    @Test
    void returnsFirstPageWithCursorAtItsLastRow() {
        Ingredient expired = ingredient(1L, TODAY.minusDays(1));
        Ingredient last = ingredient(2L, TODAY);
        when(ingredientPageReader.read(any(), eq(3))).thenReturn(List.of(expired, last, ingredient(3L, TODAY)));
        when(ingredientRepository.countByRefrigeratorId(REFRIGERATOR_ID)).thenReturn(5L);

        IngredientListResult result = ingredientListService.getList(USER_ID, REFRIGERATOR_ID, IngredientSortType.NAME_ASC, null, null, 2);

        assertThat(result.ingredients()).containsExactly(expired, last);
        assertThat(result.businessDate()).isEqualTo(TODAY);
        assertThat(result.ingredientsNum()).isEqualTo(5L);
        assertThat(result.filteredCount()).isEqualTo(5L);
        assertThat(result.refrigeratorCapacity()).isEqualTo((short) 100);
        assertThat(readCursor().group()).isEqualTo(IngredientExpiryGroup.EXPIRED);
        assertThat(cursorCodec.decode(result.nextCursor())).isEqualTo(new IngredientListCursor(IngredientSortType.NAME_ASC,
                REFRIGERATOR_ID, null, TODAY, IngredientExpiryGroup.NOT_EXPIRED, IngredientCursor.from(last)));
    }

    @Test
    void returnsNoCursorOnLastPage() {
        when(ingredientPageReader.read(any(), eq(3))).thenReturn(List.of(ingredient(1L, TODAY)));
        when(ingredientRepository.countByRefrigeratorId(REFRIGERATOR_ID)).thenReturn(1L);

        IngredientListResult result = ingredientListService.getList(USER_ID, REFRIGERATOR_ID, IngredientSortType.NAME_ASC, null, null, 2);

        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void keepsBaseDateOfCursorAfterMidnight() {
        String token = token(IngredientSortType.CREATED_DESC, REFRIGERATOR_ID, TODAY.minusDays(1));
        when(ingredientPageReader.read(any(), eq(3))).thenReturn(List.of());
        when(ingredientRepository.countByRefrigeratorId(REFRIGERATOR_ID)).thenReturn(0L);

        IngredientListResult result = ingredientListService.getList(USER_ID, REFRIGERATOR_ID, IngredientSortType.CREATED_DESC, null, token, 2);

        assertThat(result.businessDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(readCursor().baseDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void rejectsCursorIssuedForAnotherSort() {
        String token = token(IngredientSortType.NAME_ASC, REFRIGERATOR_ID, TODAY);

        assertThatThrownBy(() -> ingredientListService.getList(
                USER_ID, REFRIGERATOR_ID, IngredientSortType.CREATED_DESC, null, token, 2))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(INVALID_CURSOR));
    }

    private IngredientListCursor readCursor() {
        ArgumentCaptor<IngredientListCursor> captor = ArgumentCaptor.forClass(IngredientListCursor.class);
        verify(ingredientPageReader).read(captor.capture(), eq(3));
        return captor.getValue();
    }

    private String token(IngredientSortType sortType, Long refrigeratorId, LocalDate baseDate) {
        IngredientCursor position = new IngredientCursor(baseDate, LocalDateTime.of(2026, 9, 20, 9, 0), "두부", 7L);
        return cursorCodec.encode(new IngredientListCursor(
                sortType, refrigeratorId, null, baseDate, IngredientExpiryGroup.NOT_EXPIRED, position));
    }

    private Ingredient ingredient(Long id, LocalDate expirationDate) {
        IngredientDetails details = new IngredientDetails("두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), expirationDate);
        Ingredient ingredient = new Ingredient(new Refrigerator("냉장고", "2026-09"), details, RegistrationSource.DIRECT);
        ReflectionTestUtils.setField(ingredient, "id", id);
        ReflectionTestUtils.setField(ingredient, "createdAt", LocalDateTime.of(2026, 9, 20, 9, 0));
        return ingredient;
    }
}
