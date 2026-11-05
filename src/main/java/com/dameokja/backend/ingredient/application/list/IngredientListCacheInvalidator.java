package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.infrastructure.IngredientListCacheStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 재고 목록에 보이는 값을 바꾸는 쓰기 뒤에 냉장고 버전을 올린다.
 * 커밋 전에 올리면 그사이 조회가 옛 값을 새 버전 키에 저장할 수 있어 커밋 뒤에 올린다.
 * 실패하면 페이지 TTL까지 옛 값이 보일 수 있다.
 */
@Slf4j
@Component
public class IngredientListCacheInvalidator {
    private final IngredientListCacheStore cacheStore;
    private final boolean enabled;

    public IngredientListCacheInvalidator(IngredientListCacheStore cacheStore,
            @Value("${ingredient.list-cache.enabled:false}") boolean enabled) {
        this.cacheStore = cacheStore;
        this.enabled = enabled;
    }

    public void invalidateAfterCommit(Long refrigeratorId) {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increaseVersion(refrigeratorId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                increaseVersion(refrigeratorId);
            }
        });
    }

    private void increaseVersion(Long refrigeratorId) {
        try {
            cacheStore.increaseVersion(refrigeratorId);
        } catch (RuntimeException exception) {
            log.warn("재고 목록 캐시 버전 증가 실패. refrigeratorId={}", refrigeratorId, exception);
        }
    }
}
