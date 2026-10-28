package com.dameokja.backend.analysis.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchLeaseService;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchProperties;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchSender;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
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
    @Autowired private AnalysisDispatchLeaseService analysisLeases;
    @Autowired private AnalysisDispatchSender analysisSender;
    @Autowired private AnalysisDispatchProperties analysisPolicy;

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
        // DB의 DATETIME(6) 반올림으로 고정 시각보다 미래에 작업이 예약되지 않도록 정밀도를 맞춘다.
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
    }

    protected ResultActions submitImage(Long userId, String inputHint) throws Exception {
        uploads.save(userId, ImageUploadPurpose.ANALYSIS, OBJECT_KEY, "ab".repeat(32));
        return postAsUser(userId, "/api/v1/image-analyses",
                "{\"imageObjectKeys\":[\"" + OBJECT_KEY + "\"],\"inputHint\":\"" + inputHint + "\"}");
    }

    protected ResultActions registerInventory(Long userId, Long refrigeratorId, String analysisId) throws Exception {
        String registration = """
                {"analysisId":"%s","images":[{"imageObjectKey":"%s","items":[
                  {"itemId":"milk","name":"우유","category":"DAIRY","storageType":"REFRIGERATED",
                   "measureType":"COUNT","quantity":2,"expiration":"%s"}]}]}
                """.formatted(analysisId, OBJECT_KEY, BusinessTime.today(clock).plusDays(3));
        return postAsUser(userId, "/api/v1/refrigerators/" + refrigeratorId + "/ingredients/from-analysis", registration);
    }

    protected ResultActions queryInventory(Long userId, Long refrigeratorId) throws Exception {
        return mvc.perform(get("/api/v1/refrigerators/{refrigeratorId}/ingredients", refrigeratorId).cookie(access(userId)));
    }

    protected void assertAnalysisQueued(String analysisId) {
        assertThat(jdbcTemplate.queryForList("SELECT object_key FROM analysis_dispatches WHERE analysis_job_id = ?", String.class, analysisId))
                .contains(OBJECT_KEY);
    }

    protected void assertFeedbackQueued(Long refrigeratorId) {
        assertThat(jdbcTemplate.queryForList("SELECT feedback_body FROM analysis_feedbacks WHERE ai_analysis_id = ?", String.class, AI_ANALYSIS_ID))
                .anySatisfy(body -> {
                    assertThat(mapper.readTree(body).path("refrigeratorId").asLong()).isEqualTo(refrigeratorId);
                    assertThat(mapper.readTree(body).at("/items/0/confirmed/name").asString()).isEqualTo("우유");
                    assertThat(mapper.readTree(body).at("/items/0/confirmed/quantity").asInt()).isEqualTo(2);
                });
    }

    protected void assertNoFeedbackQueued() {
        assertThat(jdbcTemplate.queryForList("SELECT ai_analysis_id FROM analysis_feedbacks WHERE ai_analysis_id = ?", String.class, AI_ANALYSIS_ID)).isEmpty();
    }

    protected AutoCloseable rejectRegistrationStorage(String table) {
        if (!Set.of("ingredients", "analysis_feedbacks").contains(table)) {
            throw new IllegalArgumentException("등록 원자성 테스트 대상 테이블이 아닙니다.");
        }
        jdbcTemplate.execute("CREATE TRIGGER reject_atomic_registration BEFORE INSERT ON " + table
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'contract registration storage failure'");
        return () -> jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_atomic_registration");
    }

    // 재예약 시각까지 실제로 기다리지 않으며, 전송·DB 갱신이 끝난 뒤 테스트로 돌아온다.
    protected void retryDueAnalyses() {
        clock.set(clock.instant().plus(analysisPolicy.retryDelay()).toString());
        analysisLeases.leaseDue().forEach(analysisSender::send);
    }

    protected int analysisAttemptLimit() { return analysisPolicy.maxAttempts(); }

    private ResultActions postAsUser(Long userId, String path, String body) throws Exception {
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mvc.perform(post(path).cookie(access(userId), csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(body));
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
