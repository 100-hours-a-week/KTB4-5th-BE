package com.dameokja.backend.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisCompletionTest {
    private final OffsetDateTime completedAt = OffsetDateTime.parse("2026-10-13T10:00:00+09:00");
    private final AnalysisCompletionSuccess result = new AnalysisCompletionSuccess("RECEIPT", List.of());
    private final AnalysisCompletionFailure error = new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", false, null);

    @Test
    void rejectsUnfinishedStatesAndInconsistentTerminalBodies() {
        assertThatThrownBy(() -> new AnalysisCompletion(AnalysisStatus.PROCESSING, completedAt, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisCompletion(AnalysisStatus.PARTIALLY_COMPLETED, completedAt, result, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisCompletion(AnalysisStatus.COMPLETED, completedAt, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisCompletion(AnalysisStatus.COMPLETED, completedAt, result, error)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisCompletion(AnalysisStatus.FAILED, completedAt, result, error)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisCompletion(AnalysisStatus.FAILED, completedAt, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesCompletionInstantsIndependentlyOfTheirTimezoneAndKeepsItemsImmutable() {
        AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, completedAt, result, null);
        assertThat(completion.completedAt().getOffset()).isEqualTo(completedAt.getOffset());
        assertThat(completion.completedAt().toInstant()).isEqualTo(completedAt.toInstant());
        assertThat(completion).isEqualTo(new AnalysisCompletion(AnalysisStatus.COMPLETED, OffsetDateTime.parse("2026-10-13T01:00:00Z"), result, null));
        List<AnalysisCompletionItem> items = new ArrayList<>();
        AnalysisCompletionSuccess snapshot = new AnalysisCompletionSuccess("PRODUCT", items);
        items.add(null);
        assertThat(snapshot.items()).isEmpty();
        assertThatThrownBy(() -> snapshot.items().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
