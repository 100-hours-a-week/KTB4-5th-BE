package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisFeedbackRepository extends JpaRepository<AnalysisFeedback, Long> {}
