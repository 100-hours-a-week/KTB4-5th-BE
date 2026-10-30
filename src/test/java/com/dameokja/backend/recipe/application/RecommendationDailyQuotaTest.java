package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyQuotaStore;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RecommendationDailyQuotaTest extends ServiceIntegrationTest {
    @Autowired private RecommendationDailyQuotaService recommendationDailyQuotaService;
    @Autowired private RecommendationDailyQuotaStore recommendationDailyQuotaStore;
    @Autowired private RecommendationSubmissionService recommendationSubmissionService;
    @Autowired private MembershipService membershipService;
    @Autowired private InvitationService invitationService;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private AiRecipeClient aiRecipeClient;

    @Test
    void acceptsTenGenerationsAndRejectsTheEleventhWithoutChangingDatabaseState() {
        Fixture participant = ownerFixture("한도회원");
        for (int attempt = 0; attempt < 10; attempt++) {
            RecommendationSubmissionResult accepted = recommendationSubmissionService.submit(participant.userId(), participant.refrigeratorId(), null);
            assertThat(accepted.dailyGeneration().remaining()).isEqualTo(9 - attempt);
            jdbcTemplate.update("UPDATE recommendation_states SET generation_status = 'FAILED', active_request_id = NULL WHERE user_id = ?", participant.userId());
        }
        assertThatThrownBy(() -> recommendationSubmissionService.submit(participant.userId(), participant.refrigeratorId(), null))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(RecommendationExceptionCode.DAILY_LIMIT_REACHED));
        assertThat(recommendationDailyQuotaService.current(participant.userId()).remaining()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT generation_status FROM recommendation_states", String.class)).isEqualTo("FAILED");
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void sharesOneUserQuotaAcrossRefrigeratorsAndKeepsOtherUsersIndependent() {
        Fixture participant = ownerFixture("이동회원");
        Fixture sharedOwner = ownerFixture("공유회원");
        recommendationSubmissionService.submit(participant.userId(), participant.refrigeratorId(), null);
        membershipService.join(participant.userId(), invitationService.issueCode(sharedOwner.userId(), sharedOwner.refrigeratorId()).code());
        RecommendationSubmissionResult accepted = recommendationSubmissionService.submit(participant.userId(), sharedOwner.refrigeratorId(), null);
        assertThat(accepted.dailyGeneration().remaining()).isEqualTo(8);
        assertThat(recommendationDailyQuotaService.current(sharedOwner.userId()).remaining()).isEqualTo(10);
        membershipService.leave(participant.userId(), sharedOwner.refrigeratorId());
        assertThat(recommendationDailyQuotaService.current(participant.userId()).remaining()).isEqualTo(8);
    }

    @Test
    void atomicallyAllowsOnlyTenConcurrentReservations() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 17);
        List<Callable<Long>> reservations = IntStream.range(0, 20).mapToObj(index -> (Callable<Long>) () ->
                recommendationDailyQuotaStore.reserve(17L, date, UUID.randomUUID().toString(), 10, Duration.ofHours(12))).toList();
        List<Object> results = concurrently(reservations);
        assertThat(results).allMatch(Long.class::isInstance);
        assertThat(results.stream().map(Long.class::cast).filter(count -> count > 0)).hasSize(10);
        assertThat(results.stream().map(Long.class::cast).filter(count -> count == -1)).hasSize(10);
        assertThat(recommendationDailyQuotaStore.count(17L, date)).isEqualTo(10);
    }

    @Test
    void usesSeoulMidnightAndDoesNotExtendTtlOnReads() {
        clock.set("2026-09-17T14:00:00Z");
        reserve(17L, "before-midnight");
        String key = RedisTestSupport.redisTemplate().keys("recipe:quota:*").iterator().next();
        Long ttlBeforeRead = RedisTestSupport.redisTemplate().getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlBeforeRead).isPositive().isLessThanOrEqualTo(Duration.ofHours(1).toMillis());
        RecommendationDailyGeneration beforeMidnight = recommendationDailyQuotaService.current(17L);
        assertThat(beforeMidnight.limit()).isEqualTo(10);
        assertThat(beforeMidnight.remaining()).isEqualTo(9);
        assertThat(beforeMidnight.resetAt()).isEqualTo(OffsetDateTime.parse("2026-09-18T00:00:00+09:00"));
        assertThat(RedisTestSupport.redisTemplate().getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttlBeforeRead);
        clock.set("2026-09-17T15:00:00Z");
        assertThat(recommendationDailyQuotaService.current(17L).remaining()).isEqualTo(10);
        assertThat(reserve(17L, "after-midnight").remaining()).isEqualTo(9);
    }

    @Test
    void releasesOnlyItsOwnReservationWhenRedisDataWasLostAndRecreated() {
        LocalDate date = LocalDate.of(2026, 9, 17);
        reserve(17L, "lost-request");
        RedisTestSupport.redisTemplate().delete(RedisTestSupport.redisTemplate().keys("recipe:quota:*"));
        assertThat(recommendationDailyQuotaService.current(17L).remaining()).isEqualTo(10);
        reserve(17L, "new-request");
        recommendationDailyQuotaStore.release(17L, date, "lost-request");
        assertThat(recommendationDailyQuotaService.current(17L).remaining()).isEqualTo(9);
    }

    @Test
    void returnsReservationWhenTheEnclosingTransactionRollsBack() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            recommendationDailyQuotaService.reserve(17L, "rollback-request");
            assertThat(recommendationDailyQuotaService.current(17L).remaining()).isEqualTo(9);
            transactionStatus.setRollbackOnly();
        });
        assertThat(recommendationDailyQuotaService.current(17L).remaining()).isEqualTo(10);
    }

    @Test
    void doesNotReturnACommittedReservationWhenItsDuplicateRollsBack() {
        reserve(17L, "committed-request");
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            recommendationDailyQuotaService.reserve(17L, "committed-request");
            transactionStatus.setRollbackOnly();
        });
        assertThat(recommendationDailyQuotaService.current(17L).remaining()).isEqualTo(9);
    }

    private RecommendationDailyGeneration reserve(Long userId, String requestId) {
        return new TransactionTemplate(transactionManager).execute(transactionStatus -> recommendationDailyQuotaService.reserve(userId, requestId));
    }
}
