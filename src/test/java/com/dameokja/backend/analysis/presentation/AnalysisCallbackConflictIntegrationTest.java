package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.support.MySqlDatabaseTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 저장소를 직접 만드는 단위 테스트는 @Repository 예외 변환 프록시를 거치지 않으므로, 실제 빈으로 충돌 응답을 확인한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AnalysisCallbackConflictIntegrationTest extends MySqlDatabaseTest {
    private static final String PATH = "/api/v1/internal/image-analyses/callback";
    private static final String FAILED = """
            {"analysisId":"ai-conflict","status":"FAILED","completedAt":"2026-10-15T22:04:17+09:00","result":null,
             "error":{"code":"%s","message":"분석 실패","retryable":true,"retryAfterMs":5000}}
            """;
    @Autowired MockMvc mvc;
    @Autowired AnalysisJobStore store;

    @Test
    void rejectsAResultThatConflictsWithTheStoredOneAsConflict() throws Exception {
        AnalysisJob job = store.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "ai-conflict");
        send(FAILED.formatted("DEPENDENCY_UNAVAILABLE")).andExpect(status().isOk());

        send(FAILED.formatted("OTHER_CODE")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMAGE-409-001"));

        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion().error().code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post(PATH).header("X-Internal-API-Key", "test-key").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
