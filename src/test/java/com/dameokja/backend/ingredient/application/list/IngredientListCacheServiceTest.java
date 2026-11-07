package com.dameokja.backend.ingredient.application.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.infrastructure.IngredientListCacheStore;
import com.dameokja.backend.ingredient.presentation.response.IngredientListResponse;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IngredientListCacheServiceTest {
    private static final Long USER_ID = 1L;
    private static final Long REFRIGERATOR_ID = 2L;
    private static final IngredientListResponse CACHED_RESPONSE = new IngredientListResponse(LocalDate.of(2026, 11, 5), false, 3, 3, (short) 1500, "우리집", List.of(), null);

    private final IngredientListService listService = mock(IngredientListService.class);
    private final RefrigeratorAccessService accessService = mock(RefrigeratorAccessService.class);
    private final IngredientListCacheStore cacheStore = mock(IngredientListCacheStore.class);

    private IngredientListCacheService serviceWith(boolean enabled) {
        return new IngredientListCacheService(listService, accessService, cacheStore, Clock.systemUTC(), enabled, false);
    }

    @Test
    void 캐시에_있으면_권한만_확인하고_DB를_읽지_않는다() {
        given(cacheStore.currentVersion(REFRIGERATOR_ID)).willReturn(7L);
        given(cacheStore.pageKey(any(), any(Long.class), any())).willReturn("key");
        given(cacheStore.read("key")).willReturn(Optional.of(CACHED_RESPONSE));

        IngredientListView view = serviceWith(true).getList(USER_ID, REFRIGERATOR_ID, IngredientSortType.EXPIRATION_ASC, null, null, null, null, 10);

        assertThat(view.cacheStatus()).isEqualTo(IngredientListView.HIT);
        assertThat(view.response()).isEqualTo(CACHED_RESPONSE);
        verify(accessService).validateReadAccess(USER_ID, REFRIGERATOR_ID);
        verify(listService, never()).getList(any(), any(), any(), any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void 검색어가_있으면_캐시를_보지_않는다() {
        IngredientListCacheService service = serviceWith(true);
        given(listService.getList(any(), any(), any(), any(), any(), any(), any(), any(Integer.class)))
                .willReturn(new IngredientListResult(List.of(), LocalDate.of(2026, 11, 5), false, 0, 0, (short) 1500, "우리집", null));

        IngredientListView view = service.getList(USER_ID, REFRIGERATOR_ID, IngredientSortType.EXPIRATION_ASC, null, null, "양파", null, 10);

        assertThat(view.cacheStatus()).isNull();
        verify(cacheStore, never()).currentVersion(any());
    }
}
