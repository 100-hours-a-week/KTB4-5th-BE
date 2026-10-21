package com.dameokja.backend.analysis.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.presentation.request.AnalysisCallbackRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class AiImageAnalysisResponseTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    // AI 4a03494 조회 응답과, 같은 분석을 AI build_callback_payload로 바꾼 콜백 본문이다.
    @Test
    void convertsTheLookupResultTheSameWayAiBuildsTheCallback() throws IOException {
        AnalysisCompletion recovered = mapper.readValue(fixture("ai-lookup-view.json"), AiImageAnalysisResponse.class).toCompletion();
        AnalysisCompletion callback = mapper.readValue(fixture("ai-lookup-callback.json"), AnalysisCallbackRequest.class).completion();
        assertThat(recovered).isEqualTo(callback);
    }

    @Test
    void convertsAFailedLookupIntoAFailedCompletion() {
        AiImageAnalysisResponse aiImageAnalysisResponse = mapper.readValue("""
                {"analysisId":"ana_2","status":"FAILED","submittedAt":"2026-10-17T03:00:00Z","completedAt":"2026-10-17T03:00:05Z",
                 "result":null,"error":{"code":"UNSUPPORTED_IMAGE","message":"지원하지 않는 이미지","retryable":false,"retryAfterMs":null}}
                """, AiImageAnalysisResponse.class);
        AnalysisCompletion completion = aiImageAnalysisResponse.toCompletion();
        assertThat(aiImageAnalysisResponse.isFinished()).isTrue();
        assertThat(completion.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(completion.error().code()).isEqualTo("UNSUPPORTED_IMAGE");
        assertThat(completion.error().retryable()).isFalse();
    }

    @Test
    void treatsQueuedAndProcessingAsNotFinished() {
        assertThat(new AiImageAnalysisResponse("a", "QUEUED", null, null, null, null, null, null).isFinished()).isFalse();
        assertThat(new AiImageAnalysisResponse("a", "PROCESSING", null, null, null, null, null, null).isFinished()).isFalse();
    }

    private String fixture(String name) throws IOException {
        return new ClassPathResource("analysis/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
