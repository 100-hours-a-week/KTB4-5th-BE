package com.dameokja.backend.analysis.domain;

public enum AnalysisStatus {
    QUEUED, PROCESSING, COMPLETED, PARTIALLY_COMPLETED, FAILED;

    public boolean isInProgress() { return this == QUEUED || this == PROCESSING; }
}
