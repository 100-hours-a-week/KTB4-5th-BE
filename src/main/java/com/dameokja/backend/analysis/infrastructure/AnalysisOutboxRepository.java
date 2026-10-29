package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisOutboxRepository extends JpaRepository<AnalysisOutbox, Long> {}
