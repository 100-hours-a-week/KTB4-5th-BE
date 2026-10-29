package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisJobState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisJobStateRepository extends JpaRepository<AnalysisJobState, String> {}
