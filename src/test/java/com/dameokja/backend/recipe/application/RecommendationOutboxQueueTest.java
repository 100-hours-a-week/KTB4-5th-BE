package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(RecommendationOutboxQueue.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecommendationOutboxQueueTest extends MySqlJpaTest {
    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final String TEST_TOPIC = "test.recommendation.requested.v1";
    private static final String BODY = "{\"messageId\":\"11111111-1111-4111-8111-111111111111\",\"payload\":{\"userId\":42}}";
    @Autowired RecommendationOutboxQueue queue;
    @Autowired RecommendationOutboxRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean Clock clock;
    private final String requestId = UUID.randomUUID().toString();
    private Long refrigeratorId;

    @BeforeEach
    void prepare() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        refrigeratorId = transaction().execute(status -> {
            Refrigerator refrigerator = new Refrigerator("발행냉장고", "2026-11");
            entityManager.persist(refrigerator);
            return refrigerator.getId();
        });
    }

    @AfterEach
    void cleanup() {
        transaction().executeWithoutResult(status -> {
            repository.deleteAll(repository.findByRequestIdOrderByIdAsc(requestId));
            entityManager.remove(entityManager.find(Refrigerator.class, refrigeratorId));
        });
    }

    @Test
    void onlyLeasesKafkaRowsAndRetainsTheirFrozenDestinationAndBody() {
        Long httpOutboxId = save(false);
        Long kafkaOutboxId = save(true);
        RecommendationOutboxQueue.Lease lease = queue.leaseBatch(1, LEASE).getFirst();
        assertThat(lease.id()).isEqualTo(kafkaOutboxId);
        assertThat(lease.topic()).isEqualTo(TEST_TOPIC);
        assertThat(lease.key()).isEqualTo("42");
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(lease.body())).isEqualTo(mapper.readTree(BODY));
        assertThat(queue.leaseBatch(1, LEASE)).isEmpty();
        assertThat(queue.markPublished(lease)).isEqualTo(1);
        assertThat(repository.findById(kafkaOutboxId).orElseThrow().getStatus()).isEqualTo(RecommendationOutbox.Status.SENT);
        assertThat(queue.markPublished(lease)).isZero();
        assertThat(repository.findById(httpOutboxId).orElseThrow().getStatus()).isEqualTo(RecommendationOutbox.Status.PENDING);
    }

    @Test
    void reclaimsExpiredLeasesAndRejectsLateCompletionOrRetryFromThePreviousOwner() {
        save(true);
        RecommendationOutboxQueue.Lease first = queue.leaseBatch(1, LEASE).getFirst();
        when(clock.instant()).thenReturn(NOW.plus(LEASE));
        assertThat(queue.markPublished(first)).isZero();
        RecommendationOutboxQueue.Lease second = queue.leaseBatch(1, LEASE).getFirst();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(queue.markPublished(first)).isZero();
        assertThat(queue.retryLater(first, LEASE)).isZero();
        assertThat(queue.markPublished(second)).isEqualTo(1);
    }

    @Test
    void waitsUntilTheRetryDeadlineWithoutChangingTheMessage() {
        save(true);
        RecommendationOutboxQueue.Lease first = queue.leaseBatch(1, LEASE).getFirst();
        assertThat(queue.retryLater(first, Duration.ofSeconds(5))).isEqualTo(1);
        assertThat(queue.leaseBatch(1, LEASE)).isEmpty();
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        assertThat(queue.leaseBatch(1, LEASE).getFirst().body()).isEqualTo(first.body());
    }

    @Test
    void skipsRowsLockedByAnotherTransactionWithoutWaitingForItsCommit() throws Exception {
        save(true);
        CompletableFuture<Void> locked = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> owner = executor.submit(() -> transaction().executeWithoutResult(status -> {
                assertThat(repository.findPendingForUpdate("2026-11-03 12:00:00.000000", 1)).hasSize(1);
                locked.complete(null);
                release.join();
            }));
            try {
                locked.get(5, TimeUnit.SECONDS);
                Future<List<RecommendationOutboxQueue.Lease>> other = executor.submit(() -> queue.leaseBatch(1, LEASE));
                assertThat(other.get(5, TimeUnit.SECONDS)).isEmpty();
            } finally {
                release.complete(null);
            }
            owner.get(5, TimeUnit.SECONDS);
        }
        assertThat(queue.leaseBatch(1, LEASE)).hasSize(1);
    }

    private Long save(boolean kafka) {
        return transaction().execute(status -> {
            Refrigerator refrigerator = entityManager.getReference(Refrigerator.class, refrigeratorId);
            RecommendationOutbox outbox = new RecommendationOutbox(UUID.randomUUID().toString(), refrigerator, requestId, "{}", true);
            if (kafka) {
                outbox.prepareKafka(TEST_TOPIC, "42", BODY, BusinessTime.now(clock));
            }
            return repository.saveAndFlush(outbox).getId();
        });
    }

    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }
}
