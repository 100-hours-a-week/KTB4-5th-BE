package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisDispatchRepository extends JpaRepository<AnalysisDispatch, Long> {
}
