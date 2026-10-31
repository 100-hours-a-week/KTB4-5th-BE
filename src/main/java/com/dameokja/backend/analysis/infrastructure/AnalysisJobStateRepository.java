package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisJobState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AnalysisJobStateRepository extends JpaRepository<AnalysisJobState, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from AnalysisJobState job where job.id = :analysisJobId")
    Optional<AnalysisJobState> findForUpdate(String analysisJobId);
}
