package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisJobRequest;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AnalysisJobRequestRepository extends JpaRepository<AnalysisJobRequest, Long> {
    @Query("select entry.id from AnalysisJobRequest entry where entry.analysisJobState.id = :analysisJobId order by entry.id")
    List<Long> findIdsByAnalysisJobId(String analysisJobId);

    @Query("select request.analysisJobState.id from AnalysisJobRequest request where request.requestId = :requestId order by request.analysisJobState.id")
    List<String> findAnalysisJobIds(String requestId);
}
