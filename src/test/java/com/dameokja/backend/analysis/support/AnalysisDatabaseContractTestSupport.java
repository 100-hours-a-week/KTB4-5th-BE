package com.dameokja.backend.analysis.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.Import;

@Import(AnalysisDatabaseJobFixture.class)
public abstract class AnalysisDatabaseContractTestSupport extends AnalysisContractTestSupport {
    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        jdbcTemplate.update("DELETE FROM analysis_outbox");
        jdbcTemplate.update("DELETE FROM analysis_job_requests");
        jdbcTemplate.update("DELETE FROM analysis_job_states");
        super.cleanDatabaseAndResetTime();
    }
}
