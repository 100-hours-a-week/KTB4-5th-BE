package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisJobState;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AnalysisJobStateRepository extends JpaRepository<AnalysisJobState, String> {
    @Query("""
            select job from AnalysisJobState job
            where job.user.id = :userId and (job.expiresAt is null or job.expiresAt > :currentTime)
            order by job.createdAt desc, job.id desc
            """)
    List<AnalysisJobState> findAllActiveOwned(Long userId, LocalDateTime currentTime);

    @Query(value = """
            SELECT * FROM analysis_job_states
            WHERE user_id = :userId AND JSON_EXTRACT(job_body, '$.refrigeratorId') = :refrigeratorId
                AND (expires_at IS NULL OR expires_at > :currentTime)
            ORDER BY created_at DESC, analysis_job_id DESC
            """, nativeQuery = true)
    List<AnalysisJobState> findAllActiveOwnedInRefrigerator(Long userId, Long refrigeratorId, LocalDateTime currentTime);

    @Query(value = """
            SELECT * FROM analysis_job_states
            WHERE analysis_job_id = :analysisJobId AND user_id = :userId
                AND JSON_EXTRACT(job_body, '$.refrigeratorId') = :refrigeratorId
                AND (expires_at IS NULL OR expires_at > :currentTime)
            """, nativeQuery = true)
    Optional<AnalysisJobState> findActiveOwnedInRefrigerator(String analysisJobId, Long userId, Long refrigeratorId, LocalDateTime currentTime);

    @Query("""
            select count(job) > 0 from AnalysisJobState job
            where job.id = :analysisJobId and job.user.id <> :userId
                and (job.expiresAt is null or job.expiresAt > :currentTime)
            """)
    boolean existsActiveOwnedByAnotherUser(String analysisJobId, Long userId, LocalDateTime currentTime);

    @Query(value = """
            SELECT * FROM analysis_job_states
            WHERE notification_next_attempt_at <= CAST(:currentTime AS DATETIME(6))
            ORDER BY notification_next_attempt_at, analysis_job_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<AnalysisJobState> findDueNotificationForUpdate(String currentTime);

    @Query(value = """
            SELECT * FROM analysis_job_states
            WHERE expires_at <= CAST(:currentTime AS DATETIME(6))
            ORDER BY expires_at, analysis_job_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<AnalysisJobState> findExpiredForUpdate(String currentTime);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from AnalysisJobState job where job.id = :analysisJobId")
    Optional<AnalysisJobState> findForUpdate(String analysisJobId);
}
