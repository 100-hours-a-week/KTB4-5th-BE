package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse.Status;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeSubmitResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
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
    private final RecommendationOutboxRepository outbox;
    private final Clock clock;

    public RecommendationJobStore(StringRedisTemplate redis, JsonMapper mapper, @Value("${recipe.result-ttl:24h}") Duration resultTtl,
            RecommendationOutboxRepository outbox, Clock clock) {
        if (resultTtl == null || resultTtl.toMillis() < 1) {
            throw new IllegalArgumentException("추천 결과 보관 기간은 1ms 이상이어야 합니다.");
        }
        this.redis = redis;
        this.mapper = mapper;
        this.resultTtl = resultTtl;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public Optional<Job> find(String eventId) {
        return outbox.lockByEventId(eventId).flatMap(this::findJob);
    }

    @Transactional
    public Job accept(String eventId, AiRecipeSubmitResponse acceptance) {
        RecommendationOutbox event = outbox.lockByEventId(eventId).orElseThrow();
        Job incoming = new Job(acceptance, null);
        if (!event.isDbJob()) { return save(eventId, incoming); }
        Optional<Job> previous = findJob(event);
        if (previous.isPresent()) { return merge(event, previous.orElseThrow(), incoming); }
        if (event.getJobExpiresAt() != null) { throw new IllegalStateException("추천 작업이 만료되었습니다."); }
        event.saveJob(mapper.writeValueAsString(incoming), null);
        return incoming;
    }

    @Transactional
    public Job update(String eventId, AiRecipeJobResponse view) {
        RecommendationOutbox event = outbox.lockByEventId(eventId).orElseThrow();
        Job current = findJob(event).orElseThrow();
        AiRecipeSubmitResponse acceptance = current.acceptance();
        if (!acceptance.jobId().equals(view.jobId()) || !acceptance.requestId().equals(view.requestId())) {
            throw new IllegalArgumentException("추천 조회 결과가 접수한 작업과 일치하지 않습니다.");
        }
        Job incoming = new Job(acceptance, view);
        return event.isDbJob() ? merge(event, current, incoming) : save(eventId, incoming);
    }

    private Optional<Job> findJob(RecommendationOutbox event) {
        if (!event.isDbJob()) {
            return Optional.ofNullable(redis.opsForValue().get(key(event.getEventId()))).map(payload -> mapper.readValue(payload, Job.class));
        }
        if (event.getJobExpiresAt() != null && !BusinessTime.now(clock).isBefore(event.getJobExpiresAt())) {
            if (event.getJobSnapshot() != null) { event.saveJob(null, event.getJobExpiresAt()); }
            return Optional.empty();
        }
        return Optional.ofNullable(event.getJobSnapshot()).map(payload -> mapper.readValue(payload, Job.class));
    }

    private Job merge(RecommendationOutbox event, Job current, Job incoming) {
        if (!current.acceptance().jobId().equals(incoming.acceptance().jobId())
                || !current.acceptance().requestId().equals(incoming.acceptance().requestId())) {
            throw new IllegalArgumentException("추천 접수 작업이 일치하지 않습니다.");
        }
        if (current.terminal() || incoming.view() == null
                || (current.view() != null && current.view().status() == Status.PROCESSING && incoming.view().status() == Status.QUEUED)) {
            return current;
        }
        LocalDateTime expiresAt = incoming.terminal() ? BusinessTime.now(clock).plus(resultTtl) : null;
        event.saveJob(mapper.writeValueAsString(incoming), expiresAt);
        return incoming;
    }

    private Job save(String eventId, Job job) {
        String payload = redis.execute(SAVE, List.of(key(eventId)), mapper.writeValueAsString(job), Long.toString(resultTtl.toMillis()));
        return mapper.readValue(payload, Job.class);
    }

    private String key(String eventId) { return "recipe:job:" + eventId; }
}
