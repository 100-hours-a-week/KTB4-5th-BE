package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationDailyJob;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RecommendationDailyJobRepository extends JpaRepository<RecommendationDailyJob, Long> {
    @Query(value = "SELECT * FROM recommendation_daily_jobs WHERE request_id = :requestId AND status = 'ACCEPTED' FOR UPDATE", nativeQuery = true)
    Optional<RecommendationDailyJob> findAcceptedForUpdate(String requestId);

    @Modifying
    @Query(value = """
            INSERT INTO recommendation_daily_jobs (business_date, user_id, refrigerator_id, request_id, status, created_at, updated_at)
            VALUES (:businessDate, :userId, :refrigeratorId, :requestId, 'PENDING', CAST(:now AS DATETIME(6)), CAST(:now AS DATETIME(6)))
            ON DUPLICATE KEY UPDATE recommendation_daily_job_id = recommendation_daily_job_id
            """, nativeQuery = true)
    int register(LocalDate businessDate, Long userId, Long refrigeratorId, String requestId, String now);

    @Query(value = """
            SELECT * FROM recommendation_daily_jobs WHERE business_date = :businessDate
              AND (status = 'PENDING' OR (status = 'CLAIMED' AND lease_until <= CAST(:now AS DATETIME(6))))
            ORDER BY recommendation_daily_job_id LIMIT :batchSize FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RecommendationDailyJob> findReadyForUpdate(LocalDate businessDate, String now, int batchSize);

    @Query(value = """
            SELECT * FROM recommendation_daily_jobs WHERE recommendation_daily_job_id = :jobId AND status = 'CLAIMED'
              AND lease_token = :token AND lease_until > CAST(:now AS DATETIME(6)) FOR UPDATE
            """, nativeQuery = true)
    Optional<RecommendationDailyJob> findClaimedForUpdate(Long jobId, String token, String now);
}
