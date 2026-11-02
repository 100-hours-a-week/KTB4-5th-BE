package com.dameokja.backend.analysis.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.data.redis.connection.RedisConnection;
import tools.jackson.databind.ObjectMapper;

// 하위 클래스가 같은 설정과 Kafka publisher mock을 상속해 컨텍스트·DB 풀을 함께 사용한다.
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=0", "spring.kafka.listener.auto-startup=false"})
public abstract class AnalysisContractTestSupport extends ServiceIntegrationTest {
    protected static final String OBJECT_KEY = "uploads/analysis/contract.png";
    protected static final String AI_ANALYSIS_ID = "ai-contract";
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @MockitoBean protected AnalysisKafkaPublisher analysisKafkaPublisher;
    @Autowired private JwtProvider jwt;
    @Autowired private ImageUploadMetadataStore uploads;

    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        jdbcTemplate.update("DELETE FROM analysis_feedbacks");
        super.cleanDatabaseAndResetTime();
        try (RedisConnection connection = RedisTestSupport.redisTemplate().getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        // DB의 DATETIME(6) 반올림으로 고정 시각보다 미래에 작업이 예약되지 않도록 정밀도를 맞춘다.
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
    }

    protected ResultActions submitImage(Long userId, String inputHint) throws Exception {
        return submitImages(userId, List.of(OBJECT_KEY), inputHint);
    }

    protected ResultActions submitImages(Long userId, List<String> objectKeys, String inputHint) throws Exception {
        // HTTP 분석 접수는 현재 선택한 냉장고가 있어야 하므로 계약 시나리오의 접수 전제를 준비한다.
        if (refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(userId).isEmpty()) {
            join(userRepository.findById(userId).orElseThrow(), refrigerator("분석접수"), true, true);
        }
        objectKeys.forEach(key -> uploads.save(userId, ImageUploadPurpose.ANALYSIS, key, "ab".repeat(32)));
        return postAsUser(userId, "/api/v1/image-analyses", mapper.writeValueAsString(
                Map.of("imageObjectKeys", objectKeys, "inputHint", inputHint)));
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

    private ResultActions postAsUser(Long userId, String path, String body) throws Exception {
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mvc.perform(post(path).cookie(access(userId), csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions queryAnalysis(Long userId, String analysisId) throws Exception {
        return mvc.perform(get("/api/v1/image-analyses/{analysisId}", analysisId).cookie(access(userId)));
    }

    protected ResultActions listAnalyses(Long userId) throws Exception {
        return mvc.perform(get("/api/v1/image-analyses").cookie(access(userId)));
    }

    private Cookie access(Long userId) { return new Cookie("accessToken", jwt.createAccessToken(userId, UserRole.USER)); }

}
