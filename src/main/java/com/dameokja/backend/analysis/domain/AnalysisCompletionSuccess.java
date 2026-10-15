package com.dameokja.backend.analysis.domain;

import java.util.List;

public record AnalysisCompletionSuccess(String documentType, List<AnalysisCompletionItem> items) {
    public AnalysisCompletionSuccess {
        items = List.copyOf(items);
    }
}
