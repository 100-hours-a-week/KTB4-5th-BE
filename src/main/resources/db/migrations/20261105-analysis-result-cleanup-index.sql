-- analysis_job_states 생성 후, 정리 기능을 배포하기 전에 한 번 수동 적용한다.
ALTER TABLE analysis_job_states ADD INDEX ix_analysis_job_states_expiry (expires_at, analysis_job_id);
