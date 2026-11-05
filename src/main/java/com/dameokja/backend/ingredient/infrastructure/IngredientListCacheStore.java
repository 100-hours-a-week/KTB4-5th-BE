package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.presentation.response.IngredientListResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * 재고 목록 첫 페이지 캐시. 키에 냉장고별 버전을 넣어, 재고가 바뀌면 버전만 올리고 키를 하나씩 지우지 않는다.
 */
@Repository
public class IngredientListCacheStore {
    private static final String VERSION_PREFIX = "ingredient:list:version:";
    private static final String PAGE_PREFIX = "ingredient:list:page:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration pageTtl;

    public IngredientListCacheStore(StringRedisTemplate redis, ObjectMapper mapper,
            @Value("${ingredient.list-cache.ttl:60s}") Duration pageTtl) {
        this.redis = redis;
        this.mapper = mapper;
        this.pageTtl = pageTtl;
    }

    public long currentVersion(Long refrigeratorId) {
        String version = redis.opsForValue().get(VERSION_PREFIX + refrigeratorId);
        return version == null ? 0L : Long.parseLong(version);
    }

    public String pageKey(Long refrigeratorId, long version, String pageConditions) {
        return PAGE_PREFIX + refrigeratorId + ":" + version + ":" + pageConditions;
    }

    public Optional<IngredientListResponse> read(String pageKey) {
        String page = redis.opsForValue().get(pageKey);
        return page == null ? Optional.empty() : Optional.of(mapper.readValue(page, IngredientListResponse.class));
    }

    public void write(String pageKey, IngredientListResponse response) {
        redis.opsForValue().set(pageKey, mapper.writeValueAsString(response), pageTtl);
    }

    public void increaseVersion(Long refrigeratorId) {
        redis.opsForValue().increment(VERSION_PREFIX + refrigeratorId);
    }
}
