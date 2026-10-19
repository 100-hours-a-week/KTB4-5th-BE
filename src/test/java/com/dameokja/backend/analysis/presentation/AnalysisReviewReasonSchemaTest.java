package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.presentation.response.AnalysisJobResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AnalysisReviewReasonSchemaTest {
    @Test
    void documentsStructuredReasonsAndRequiredMessages() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(AnalysisJobResponse.class);
        Schema reason = schemas.get("AnalysisReviewReason");
        assertThat(reason).isNotNull();
        assertThat(reason.getRequired()).contains("field", "code", "messages");
        Schema field = (Schema) reason.getProperties().get("field");
        Schema code = (Schema) reason.getProperties().get("code");
        Schema messages = (Schema) reason.getProperties().get("messages");
        assertThat(field.getEnum()).contains("EXPIRATION", "IMAGE", "QUANTITY");
        assertThat(code.getEnum()).contains("EXPIRATION_ESTIMATED", "IMAGE_QUALITY_REVIEW_REQUIRED", "QUANTITY_DEFAULTED");
        assertThat(messages.getMinItems()).isEqualTo(1);
        assertThat(messages.getItems().getMinLength()).isEqualTo(1);
    }

    @Test
    void completedExampleUsesThePublishedReasonFormat() {
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(AnalysisApiExamples.REVIEW_RESPONSE).at("/data/results/0/items/0/reviewReasons/0/field").asString())
                .isEqualTo("EXPIRATION");
        assertThat(mapper.readTree(AnalysisApiExamples.REVIEW_RESPONSE).at("/data/results/0/items/0/reviewReasons/0/messages").isArray()).isTrue();
    }
}
