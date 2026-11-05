package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.infrastructure.IngredientListCacheStore;
import com.dameokja.backend.ingredient.presentation.response.IngredientListResponse;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.time.Clock;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 검색어·카테고리·커서가 없는 탭 첫 페이지만 캐시한다. 권한 확인은 캐시 앞에서 매번 한다.
 * 버전을 DB 조회보다 먼저 읽어야, 조회 중에 재고가 바뀌어도 옛 값이 새 버전 키에 저장되지 않는다.
 */
@Slf4j
@Service
public class IngredientListCacheService {
    private final IngredientListService ingredientListService;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientListCacheStore cacheStore;
    private final Clock clock;
    private final boolean enabled;

    public IngredientListCacheService(IngredientListService ingredientListService, RefrigeratorAccessService refrigeratorAccessService,
            IngredientListCacheStore cacheStore, Clock clock, @Value("${ingredient.list-cache.enabled:false}") boolean enabled) {
        this.ingredientListService = ingredientListService;
        this.refrigeratorAccessService = refrigeratorAccessService;
        this.cacheStore = cacheStore;
        this.clock = clock;
        this.enabled = enabled;
    }

    public IngredientListView getList(Long userId, Long refrigeratorId, IngredientSortType sortType, IngredientFilter filter,
            IngredientCategory category, String keyword, String cursorToken, int size) {
        if (!enabled || category != null || keyword != null || cursorToken != null) {
            return IngredientListView.uncached(IngredientListResponse.from(
                    ingredientListService.getList(userId, refrigeratorId, sortType, filter, category, keyword, cursorToken, size)));
        }
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        Optional<String> pageKey = findPageKey(refrigeratorId, sortType, filter, size);
        Optional<IngredientListResponse> cached = pageKey.flatMap(this::readPage);
        if (cached.isPresent()) {
            return new IngredientListView(cached.orElseThrow(), IngredientListView.HIT);
        }
        IngredientListResponse response = IngredientListResponse.from(
                ingredientListService.getList(userId, refrigeratorId, sortType, filter, null, null, null, size));
        pageKey.ifPresent(key -> writePage(key, response));
        return new IngredientListView(response, IngredientListView.MISS);
    }

    // 캐시 장애가 목록 조회 장애로 번지지 않도록 Redis 오류는 DB 조회로 넘긴다.
    private Optional<String> findPageKey(Long refrigeratorId, IngredientSortType sortType, IngredientFilter filter, int size) {
        try {
            String pageConditions = BusinessTime.today(clock) + ":" + sortType + ":" + filter + ":" + size;
            return Optional.of(cacheStore.pageKey(refrigeratorId, cacheStore.currentVersion(refrigeratorId), pageConditions));
        } catch (RuntimeException exception) {
            log.warn("재고 목록 캐시 버전 조회 실패. refrigeratorId={}", refrigeratorId, exception);
            return Optional.empty();
        }
    }

    private Optional<IngredientListResponse> readPage(String pageKey) {
        try {
            return cacheStore.read(pageKey);
        } catch (RuntimeException exception) {
            log.warn("재고 목록 캐시 읽기 실패. pageKey={}", pageKey, exception);
            return Optional.empty();
        }
    }

    private void writePage(String pageKey, IngredientListResponse response) {
        try {
            cacheStore.write(pageKey, response);
        } catch (RuntimeException exception) {
            log.warn("재고 목록 캐시 쓰기 실패. pageKey={}", pageKey, exception);
        }
    }
}
