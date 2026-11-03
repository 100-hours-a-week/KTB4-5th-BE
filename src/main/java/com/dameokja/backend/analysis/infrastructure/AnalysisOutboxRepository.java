package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisOutboxRepository extends JpaRepository<AnalysisOutbox, Long> {
    @Query("select entry.id from AnalysisOutbox entry where entry.analysisJobState.id = :analysisJobId order by entry.id")
    List<Long> findIdsByAnalysisJobId(String analysisJobId);

    @Query(value = """
            SELECT * FROM analysis_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= CAST(:currentTime AS DATETIME(6))
            ORDER BY next_attempt_at, analysis_outbox_id LIMIT :batchSize FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AnalysisOutbox> findPendingForUpdate(@Param("currentTime") String currentTime, @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT * FROM analysis_outbox
            WHERE status = 'PUBLISHING' AND lease_until <= CAST(:currentTime AS DATETIME(6))
            ORDER BY lease_until, analysis_outbox_id LIMIT :batchSize FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AnalysisOutbox> findExpiredLeaseForUpdate(@Param("currentTime") String currentTime, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE analysis_outbox SET status = 'PUBLISHED', lease_token = NULL, lease_until = NULL,
                published_at = CAST(:currentTime AS DATETIME(6)), last_error_code = NULL, updated_at = CAST(:currentTime AS DATETIME(6))
            WHERE analysis_outbox_id = :outboxId AND status = 'PUBLISHING' AND lease_token = :leaseToken
                AND lease_until > CAST(:currentTime AS DATETIME(6))
            """, nativeQuery = true)
    int markPublished(@Param("outboxId") Long outboxId, @Param("leaseToken") String leaseToken, @Param("currentTime") String currentTime);

    @Modifying
    @Query(value = """
            UPDATE analysis_outbox SET status = 'PENDING', lease_token = NULL, lease_until = NULL,
                next_attempt_at = CAST(:nextAttemptAt AS DATETIME(6)), last_error_code = 'PUBLISH_FAILED', updated_at = CAST(:currentTime AS DATETIME(6))
            WHERE analysis_outbox_id = :outboxId AND status = 'PUBLISHING' AND lease_token = :leaseToken
                AND lease_until > CAST(:currentTime AS DATETIME(6))
            """, nativeQuery = true)
    int retryLater(@Param("outboxId") Long outboxId, @Param("leaseToken") String leaseToken,
            @Param("currentTime") String currentTime, @Param("nextAttemptAt") String nextAttemptAt);
}
