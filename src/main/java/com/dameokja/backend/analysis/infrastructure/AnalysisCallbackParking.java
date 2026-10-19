package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

@Repository
public class AnalysisCallbackParking {
    private static final String PREFIX = "analysis:parked:";
    private final Duration parkedCallbackTtl;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AnalysisCallbackParking(@Value("${analysis.parked-callback-ttl:10m}") Duration parkedCallbackTtl, StringRedisTemplate redis, ObjectMapper mapper) {
        if (parkedCallbackTtl == null || parkedCallbackTtl.toMillis() < 1) {
            throw new IllegalArgumentException("보관 콜백 TTL은 1밀리초 이상이어야 합니다.");
        }
        this.parkedCallbackTtl = parkedCallbackTtl;
        this.redis = redis;
        this.mapper = mapper;
    }

    public void park(String analysisId, AnalysisCompletion completion) {
        redis.opsForValue().set(PREFIX + analysisId, mapper.writeValueAsString(completion), parkedCallbackTtl);
    }

    public Optional<AnalysisCompletion> find(String analysisId) {
        String storedJson = redis.opsForValue().get(PREFIX + analysisId);
        return Optional.ofNullable(storedJson).map(json -> mapper.readerFor(AnalysisCompletion.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(json));
    }

    public void remove(String analysisId) {
        redis.delete(PREFIX + analysisId);
    }
}
