package com.dameokja.backend.analysis;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStores;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class AnalysisStoredResultFixture {
    private AnalysisStoredResultFixture() {}

    public static void store(AnalysisJobStore store, ObjectNode view, Instant now) {
        AnalysisStatus status = AnalysisStatus.valueOf(view.path("status").asString());
        if (status.isInProgress()) { return; }
        JsonMapper mapper = JsonMapper.builder().build();
        ObjectNode result = status == AnalysisStatus.COMPLETED ? (ObjectNode) view.get("result") : null;
        if (status == AnalysisStatus.COMPLETED) {
            result.put("documentType", result.path("documentType").path("value").asString());
            for (JsonNode node : result.path("items")) {
                ObjectNode item = (ObjectNode) node;
                for (String field : java.util.List.of("name", "category", "quantity")) {
                    JsonNode value = item.path(field).path("value");
                    item.set(field, value.isMissingNode() ? mapper.nullNode() : value);
                }
            }
            result.remove(java.util.List.of("imageQuality", "modelTrace"));
        }
        AnalysisCompletionSuccess completed = status == AnalysisStatus.COMPLETED ? mapper.treeToValue(result, AnalysisCompletionSuccess.class) : null;
        AnalysisCompletionFailure error = status == AnalysisStatus.FAILED ? mapper.treeToValue(view.get("error"), AnalysisCompletionFailure.class) : null;
        AnalysisJobStores.complete(store, view.path("analysisId").asString(), new AnalysisCompletion(status, now.atZone(BusinessTime.ZONE).toOffsetDateTime(), completed, error));
    }
}
