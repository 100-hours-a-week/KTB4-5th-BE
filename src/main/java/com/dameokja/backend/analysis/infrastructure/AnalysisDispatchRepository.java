package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisDispatchRepository extends JpaRepository<AnalysisDispatch, Long> {
    @Query(value = "SELECT gate_id FROM analysis_dispatch_gates WHERE gate_id = 1 FOR UPDATE", nativeQuery = true)
    Optional<Integer> lockGate();

    @Query(value = "SELECT COUNT(*) FROM analysis_dispatches WHERE status IN ('DISPATCHING','AWAITING_CALLBACK','CHECKING')",
            nativeQuery = true)
    int countInFlight();

    @Query(value = """
            SELECT analysis_dispatch_id
            FROM analysis_dispatches
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at, analysis_dispatch_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findDueIdsWithSkipLock(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Query(value = """
            SELECT analysis_dispatch_id
            FROM analysis_dispatches
            WHERE status IN ('DISPATCHING','AWAITING_CALLBACK','CHECKING') AND lease_until <= :now
            ORDER BY lease_until
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findLeaseExpiredIdsWithSkipLock(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE analysis_dispatches
            SET status = 'DISPATCHING', lease_token = :leaseToken, lease_until = :leaseUntil, dispatched_at = :now, updated_at = :now
            WHERE analysis_dispatch_id IN (:ids)
            """, nativeQuery = true)
    int markDispatching(@Param("ids") List<Long> ids, @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    // 전송 중에 BE가 멈춰 AI 작업 ID가 없으면 조회할 대상이 없으므로 바로 다시 보낸다.
    @Modifying
    @Query(value = """
            UPDATE analysis_dispatches
            SET status = IF(ai_analysis_id IS NULL, 'PENDING', 'CHECKING'),
                lease_token = IF(ai_analysis_id IS NULL, NULL, :leaseToken),
                lease_until = IF(ai_analysis_id IS NULL, NULL, :leaseUntil),
                next_attempt_at = :now, updated_at = :now
            WHERE analysis_dispatch_id IN (:ids)
            """, nativeQuery = true)
    int markChecking(@Param("ids") List<Long> ids, @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Query("select analysisDispatch from AnalysisDispatch analysisDispatch where analysisDispatch.id = :id and analysisDispatch.leaseToken = :leaseToken")
    Optional<AnalysisDispatch> findLeased(@Param("id") Long id, @Param("leaseToken") String leaseToken);

    @Modifying
    @Query(value = """
            UPDATE analysis_dispatches
            SET status = 'AWAITING_CALLBACK', ai_analysis_id = :aiAnalysisId, lease_until = :callbackDeadline,
                accepted_at = :now, last_error_code = NULL, updated_at = :now
            WHERE analysis_dispatch_id = :id AND status = 'DISPATCHING' AND lease_token = :leaseToken
            """, nativeQuery = true)
    int accept(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("aiAnalysisId") String aiAnalysisId,
            @Param("now") LocalDateTime now, @Param("callbackDeadline") LocalDateTime callbackDeadline);

    @Modifying
    @Query(value = """
            UPDATE analysis_dispatches
            SET status = 'PENDING', lease_token = NULL, lease_until = NULL, next_attempt_at = :nextAttemptAt,
                attempt_count = attempt_count + :attemptIncrement, last_error_code = :errorCode, updated_at = :now
            WHERE analysis_dispatch_id = :id AND status IN ('DISPATCHING','CHECKING') AND lease_token = :leaseToken
            """, nativeQuery = true)
    int reschedule(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("attemptIncrement") int attemptIncrement, @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE analysis_dispatches
            SET status = 'AWAITING_CALLBACK', lease_until = :callbackDeadline, last_ai_status = :aiStatus, updated_at = :now
            WHERE analysis_dispatch_id = :id AND status = 'CHECKING' AND lease_token = :leaseToken
            """, nativeQuery = true)
    int extend(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("aiStatus") String aiStatus,
            @Param("now") LocalDateTime now, @Param("callbackDeadline") LocalDateTime callbackDeadline);

    @Modifying
    @Query(value = """
            UPDATE analysis_dispatches
            SET status = 'FAILED', lease_token = NULL, lease_until = NULL, last_error_code = :errorCode,
                finished_at = :now, updated_at = :now
            WHERE analysis_dispatch_id = :id AND status IN ('DISPATCHING','CHECKING') AND lease_token = :leaseToken
            """, nativeQuery = true)
    int fail(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE analysis_dispatches
            SET status = 'DONE', lease_token = NULL, lease_until = NULL, finished_at = :now, updated_at = :now
            WHERE request_id = :requestId AND analysis_job_id = :analysisJobId AND status NOT IN ('DONE','FAILED')
            """, nativeQuery = true)
    int finish(@Param("requestId") String requestId, @Param("analysisJobId") String analysisJobId, @Param("now") LocalDateTime now);
}
