package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse.Status;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeSubmitResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RecommendationJobStore {
    public record Job(AiRecipeSubmitResponse acceptance, AiRecipeJobResponse view) {
        public boolean terminal() { return view != null && (view.status() == Status.COMPLETED || view.status() == Status.FAILED); }
    }
    private static final RedisScript<String> SAVE = RedisScript.of(new ClassPathResource("redis/recipe/save-job.lua"), String.class);
    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final Duration resultTtl;

    public RecommendationJobStore(StringRedisTemplate redis, JsonMapper mapper, @Value("${recipe.result-ttl:24h}") Duration resultTtl) {
        if (resultTtl == null || resultTtl.toMillis() < 1) {
            throw new IllegalArgumentException("추천 결과 보관 기간은 1ms 이상이어야 합니다.");
        }
        this.redis = redis;
        this.mapper = mapper;
        this.resultTtl = resultTtl;
    }

    public Optional<Job> find(String eventId) {
        return Optional.ofNullable(redis.opsForValue().get(key(eventId))).map(payload -> mapper.readValue(payload, Job.class));
    }

    public Job accept(String eventId, AiRecipeSubmitResponse acceptance) {
        return save(eventId, new Job(acceptance, null));
    }

    public Job update(String eventId, AiRecipeJobResponse view) {
        AiRecipeSubmitResponse acceptance = find(eventId).orElseThrow().acceptance();
        if (!acceptance.jobId().equals(view.jobId()) || !acceptance.requestId().equals(view.requestId())) {
            throw new IllegalArgumentException("추천 조회 결과가 접수한 작업과 일치하지 않습니다.");
        }
        return save(eventId, new Job(acceptance, view));
    }

    private Job save(String eventId, Job job) {
        String payload = redis.execute(SAVE, List.of(key(eventId)), mapper.writeValueAsString(job), Long.toString(resultTtl.toMillis()));
        return mapper.readValue(payload, Job.class);
    }

    private String key(String eventId) { return "recipe:job:" + eventId; }
}
