package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import java.time.LocalDateTime;
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
            WHERE status = 'PENDING' AND next_attempt_at <= :now AND expires_at > :now
            ORDER BY next_attempt_at, analysis_feedback_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findDueIdsWithSkipLock(@Param("now") LocalDateTime now, @Param("limit") int limit);

    // 전송 중에 BE가 멈춰 선점 기한이 지난 행을 다시 가져간다.
    @Query(value = """
            SELECT analysis_feedback_id
            FROM analysis_feedbacks
            WHERE status = 'SENDING' AND lease_until <= :now AND expires_at > :now
            ORDER BY lease_until, analysis_feedback_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findLeaseExpiredIdsWithSkipLock(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'SENDING', lease_token = :leaseToken, lease_until = :leaseUntil, updated_at = :now
            WHERE analysis_feedback_id IN (:ids)
            """, nativeQuery = true)
    int markSending(@Param("ids") List<Long> ids, @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    Optional<AnalysisFeedback> findByIdAndLeaseToken(Long id, String leaseToken);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'DONE', lease_token = NULL, lease_until = NULL, last_error_code = NULL, finished_at = :now, updated_at = :now
            WHERE analysis_feedback_id = :id AND status = 'SENDING' AND lease_token = :leaseToken
            """, nativeQuery = true)
    int finish(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'PENDING', lease_token = NULL, lease_until = NULL, next_attempt_at = :nextAttemptAt,
                attempt_count = attempt_count + 1, last_error_code = :errorCode, updated_at = :now
            WHERE analysis_feedback_id = :id AND status = 'SENDING' AND lease_token = :leaseToken
            """, nativeQuery = true)
    int reschedule(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'FAILED', lease_token = NULL, lease_until = NULL, attempt_count = attempt_count + 1,
                last_error_code = :errorCode, finished_at = :now, updated_at = :now
            WHERE analysis_feedback_id = :id AND status = 'SENDING' AND lease_token = :leaseToken
            """, nativeQuery = true)
    int fail(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE analysis_feedbacks
            SET status = 'FAILED', lease_token = NULL, lease_until = NULL, last_error_code = :errorCode, finished_at = :now, updated_at = :now
            WHERE status IN ('PENDING','SENDING') AND expires_at <= :now AND (status = 'PENDING' OR lease_until <= :now)
            """, nativeQuery = true)
    int failExpired(@Param("errorCode") String errorCode, @Param("now") LocalDateTime now);
}
