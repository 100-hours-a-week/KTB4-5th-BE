package com.dameokja.backend.analysis.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.data.redis.connection.RedisConnection;
import tools.jackson.databind.ObjectMapper;

// 하위 클래스가 같은 설정과 AI mock을 상속해 컨텍스트·DB 풀을 함께 사용한다.
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=0"})
public abstract class AnalysisContractTestSupport extends ServiceIntegrationTest {
    protected static final String OBJECT_KEY = "uploads/analysis/contract.png";
    protected static final String AI_ANALYSIS_ID = "ai-contract";
    protected static final Duration WAIT_LIMIT = Duration.ofSeconds(5);
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @MockitoBean protected AiAnalysisClient ai;
    @Autowired private JwtProvider jwt;
    @Autowired private ImageUploadMetadataStore uploads;

    @BeforeEach
    void acceptAiRequests() {
        when(ai.ready()).thenReturn(true);
        when(ai.submit(any())).thenReturn(new AiImageAnalysisSubmitResponse(AI_ANALYSIS_ID, "QUEUED", null, 1000));
    }

    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        jdbcTemplate.update("DELETE FROM analysis_feedbacks");
        jdbcTemplate.update("DELETE FROM analysis_dispatches");
        super.cleanDatabaseAndResetTime();
        try (RedisConnection connection = RedisTestSupport.redisTemplate().getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        clock.set(Instant.now().toString());
    }

    protected ResultActions submitImage(Long userId, String inputHint) throws Exception {
        uploads.save(userId, ImageUploadPurpose.ANALYSIS, OBJECT_KEY, "ab".repeat(32));
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mvc.perform(post("/api/v1/image-analyses").cookie(access(userId), csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageObjectKeys\":[\"" + OBJECT_KEY + "\"],\"inputHint\":\"" + inputHint + "\"}"));
    }

    protected ResultActions queryAnalysis(Long userId, String analysisId) throws Exception {
        return mvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisId).cookie(access(userId)));
    }

    protected ResultActions completeAnalysis(String aiAnalysisId, String documentType) throws Exception {
        String completion = """
                {"analysisId":"%s","status":"COMPLETED","completedAt":"2026-09-17T03:00:00Z",
                 "result":{"documentType":"%s","items":[
                   {"itemId":"milk","displayStatus":"RECOGNIZED","name":"우유","category":"DAIRY","storageType":"REFRIGERATED",
                    "measureType":"COUNT","quantity":2,"weight":{"value":null,"unit":"NONE"},
                    "expiration":{"date":"2026-09-20","dateType":"USE_BY","estimatedRange":null},"reviewReasons":[]}]},"error":null}
                """.formatted(aiAnalysisId, documentType);
        return mvc.perform(post("/api/v1/internal/image-analyses/callback").header("X-Internal-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON).content(completion));
    }

    private Cookie access(Long userId) { return new Cookie("accessToken", jwt.createAccessToken(userId, UserRole.USER)); }

}
