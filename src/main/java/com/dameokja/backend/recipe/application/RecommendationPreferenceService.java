package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationPreferenceService {
    private final StringRedisTemplate redis;
    private final JsonMapper mapper;

    public RecommendationOptions last(Long userId) {
        try {
            String saved = redis.opsForValue().get(key(userId));
            if (saved != null) { return mapper.readValue(saved, RecommendationOptions.class).effective(); }
        } catch (RuntimeException exception) { log.warn("마지막 추천 옵션 조회 실패. userId={}", userId, exception); }
        return RecommendationOptions.empty().effective();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(AiRecipeRecommendationRequest request) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { save(request); }
        });
    }

    private void save(AiRecipeRecommendationRequest request) {
        try { redis.opsForValue().set(key(request.userId()), mapper.writeValueAsString(RecommendationOptions.from(request).effective())); }
        catch (RuntimeException exception) { log.warn("마지막 추천 옵션 저장 실패. userId={}", request.userId(), exception); }
    }

    private String key(Long userId) { return "recipe:preferences:" + userId; }
}
