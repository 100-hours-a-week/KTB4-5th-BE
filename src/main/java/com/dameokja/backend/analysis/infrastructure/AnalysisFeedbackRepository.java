package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisFeedbackRepository extends JpaRepository<AnalysisFeedback, Long> {
    // 상태별로 나눠 조회해야 (status, 시각) 인덱스 범위만 읽는다. 끝난 행이 쌓여도 조회 비용이 늘지 않는다.
    @Query(value = """
            SELECT analysis_feedback_id
            FROM analysis_feedbacks
            WHERE status = 'PENDING' AND next_attempt_at <= CAST(:now AS DATETIME(6)) AND expires_at > CAST(:now AS DATETIME(6))
            ORDER BY next_attempt_at, analysis_feedback_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findDueIdsWithSkipLock(@Param("now") String now, @Param("limit") int limit);

    // 전송 중에 BE가 멈춰 선점 기한이 지난 행을 다시 가져간다.
    @Query(value = """
            SELECT analysis_feedback_id
            FROM analysis_feedbacks
            WHERE status = 'SENDING' AND lease_until <= CAST(:now AS DATETIME(6)) AND expires_at > CAST(:now AS DATETIME(6))
            ORDER BY lease_until, analysis_feedback_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findLeaseExpiredIdsWithSkipLock(@Param("now") String now, @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'SENDING', lease_token = :leaseToken, lease_until = CAST(:leaseUntil AS DATETIME(6)), updated_at = CAST(:now AS DATETIME(6))
            WHERE analysis_feedback_id IN (:ids)
            """, nativeQuery = true)
    int markSending(@Param("ids") List<Long> ids, @Param("leaseToken") String leaseToken,
            @Param("now") String now, @Param("leaseUntil") String leaseUntil);

    Optional<AnalysisFeedback> findByIdAndLeaseToken(Long id, String leaseToken);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'DONE', lease_token = NULL, lease_until = NULL, last_error_code = NULL, finished_at = CAST(:now AS DATETIME(6)), updated_at = CAST(:now AS DATETIME(6))
            WHERE analysis_feedback_id = :id AND status = 'SENDING' AND lease_token = :leaseToken
            """, nativeQuery = true)
    int finish(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("now") String now);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'PENDING', lease_token = NULL, lease_until = NULL, next_attempt_at = CAST(:nextAttemptAt AS DATETIME(6)),
                attempt_count = attempt_count + 1, last_error_code = :errorCode, updated_at = CAST(:now AS DATETIME(6))
            WHERE analysis_feedback_id = :id AND status = 'SENDING' AND lease_token = :leaseToken
            """, nativeQuery = true)
    int reschedule(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("nextAttemptAt") String nextAttemptAt,
            @Param("errorCode") String errorCode, @Param("now") String now);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'FAILED', lease_token = NULL, lease_until = NULL, attempt_count = attempt_count + 1,
                last_error_code = :errorCode, finished_at = CAST(:now AS DATETIME(6)), updated_at = CAST(:now AS DATETIME(6))
            WHERE analysis_feedback_id = :id AND status = 'SENDING' AND lease_token = :leaseToken
            """, nativeQuery = true)
    int fail(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("errorCode") String errorCode, @Param("now") String now);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'FAILED', lease_token = NULL, lease_until = NULL, last_error_code = :errorCode, finished_at = CAST(:now AS DATETIME(6)), updated_at = CAST(:now AS DATETIME(6))
            WHERE status IN ('PENDING','SENDING') AND expires_at <= CAST(:now AS DATETIME(6)) AND (status = 'PENDING' OR lease_until <= CAST(:now AS DATETIME(6)))
            """, nativeQuery = true)
    int failExpired(@Param("errorCode") String errorCode, @Param("now") String now);
}
