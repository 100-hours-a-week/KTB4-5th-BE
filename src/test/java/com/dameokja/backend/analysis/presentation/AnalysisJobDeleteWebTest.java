package com.dameokja.backend.analysis.presentation;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.AnalysisJobDeleteService;
import com.dameokja.backend.analysis.application.AnalysisJobQueryService;
import com.dameokja.backend.analysis.application.AnalysisJobSubmitService;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStores;
import com.dameokja.backend.auth.presentation.CsrfController;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.OAuthSecurityConfig;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@MockitoBean(types = {OAuthSecurityConfig.class, com.dameokja.backend.user.application.UserAccessService.class})
@SpringJUnitConfig(AnalysisJobDeleteWebTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {"jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d", "analysis.job-ttl=1h", "analysis.result-ttl=7d"})
@ExtendWith(RedisTestSupport.class)
class AnalysisJobDeleteWebTest {
    private static final String PATH = "/api/v1/image-analyses/{analysisId}";
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtProvider jwtProvider;
    @Autowired Clock clock;
    @Autowired AnalysisJobStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() { mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build(); }

    @Test
    void deletesAFinishedJobThatWasNeverRegisteredAsIngredients() throws Exception {
        AnalysisJob job = job(7L, "receipt", "blur");
        AnalysisJobStores.complete(store, "ai-receipt", success());
        AnalysisJobStores.complete(store, "ai-blur", failure());

        deleteJob(job.id(), 7L).andExpect(status().isNoContent()).andExpect(content().string(""));

        mockMvc.perform(get(PATH, job.id()).cookie(accessToken(7L)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE-404-001"));
        mockMvc.perform(get("/api/v1/image-analyses").cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.analyses").isEmpty());
        deleteJob(job.id(), 7L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE-404-001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING"})
    void keepsJobsThatStillHaveImagesInProgress(String jobStatus) throws Exception {
        AnalysisJob job;
        if (jobStatus.equals("QUEUED")) {
            job = store.create(7L, "AUTO", List.of(AnalysisImage.pending("receipt", "ab".repeat(32))));
        } else {
            job = job(7L, "receipt", "blur");
            AnalysisJobStores.complete(store, "ai-receipt", success());
        }

        deleteJob(job.id(), 7L).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMAGE-409-002"));

        mockMvc.perform(get(PATH, job.id()).cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(jobStatus));
    }

    @Test
    void rejectsAnotherUsersJobAndUnknownJobs() throws Exception {
        AnalysisJob job = job(8L, "receipt");
        AnalysisJobStores.complete(store, "ai-receipt", success());

        deleteJob(job.id(), 7L).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GLOBAL-403-001"));
        deleteJob("missing", 7L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE-404-001"));
        mockMvc.perform(get(PATH, job.id()).cookie(accessToken(8L))).andExpect(status().isOk());
    }

    @Test
    void requiresLoginAndCsrf() throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(delete(PATH, "any").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
        mockMvc.perform(delete(PATH, "any").cookie(accessToken(7L)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
    }

    private ResultActions deleteJob(String analysisId, Long userId) throws Exception {
        Cookie csrf = csrf();
        return mockMvc.perform(delete(PATH, analysisId).cookie(csrf, accessToken(userId)).header("X-XSRF-TOKEN", csrf.getValue()));
    }

    private AnalysisJob job(Long userId, String... objectKeys) {
        List<AnalysisImage> images = List.of(objectKeys).stream().map(key -> AnalysisImage.pending(key, "ab".repeat(32))).toList();
        AnalysisJob job = store.create(userId, "AUTO", images);
        for (AnalysisImage image : job.analysisImages()) {
            store.register(job.id(), userId, image.requestId(), "ai-" + image.objectKey());
        }
        return job;
    }

    private AnalysisCompletion success() {
        return new AnalysisCompletion(AnalysisStatus.COMPLETED, now(), new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of()), null);
    }

    private AnalysisCompletion failure() {
        return new AnalysisCompletion(AnalysisStatus.FAILED, now(), null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, 1000));
    }

    private OffsetDateTime now() { return clock.instant().atZone(BusinessTime.ZONE).toOffsetDateTime(); }

    private Cookie csrf() throws Exception { return mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN"); }

    private Cookie accessToken(Long userId) { return new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)); }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({RedisTestSupport.Configuration.class, SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class, CsrfController.class,
            GlobalExceptionHandler.class, AnalysisController.class, AnalysisJobQueryService.class, AnalysisJobDeleteService.class, AnalysisJobStore.class})
    static class WebConfiguration {
        @Bean
        static ConversionService conversionService() { return new ApplicationConversionService(); }
        // 결과 만료 시각을 Redis 키 만료에도 쓰므로 실제 현재 시각을 기준으로 둔다.
        @Bean
        Clock clock() { return Clock.fixed(Instant.now(), BusinessTime.ZONE); }
        @Bean
        ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean
        AnalysisJobSubmitService analysisJobSubmitService() { return mock(AnalysisJobSubmitService.class); }
    }
}
