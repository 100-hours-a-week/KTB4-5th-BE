package com.dameokja.backend.analysis.presentation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.AnalysisJobQueryService;
import com.dameokja.backend.analysis.application.AnalysisJobSubmitService;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisExpirationDateType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.OAuthSecurityConfig;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@MockitoBean(types = OAuthSecurityConfig.class)
@SpringJUnitConfig(AnalysisJobListWebTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {"jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d", "analysis.job-ttl=1h", "analysis.result-ttl=7d"})
@ExtendWith(RedisTestSupport.class)
class AnalysisJobListWebTest {
    private static final Instant INSTANT = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtProvider jwtProvider;
    @Autowired Clock clock;
    @Autowired AnalysisJobStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        at(INSTANT);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void summarizesTheUsersJobsNewestFirstWithImageCountsAndItemCounts() throws Exception {
        AnalysisJob mixed = job(7L, "receipt", "failed", "pending");
        complete("ai-receipt", success(item("item-1", RecognitionStatus.RECOGNIZED), item("item-2", RecognitionStatus.NEEDS_REVIEW)));
        complete("ai-failed", new AnalysisCompletion(AnalysisStatus.FAILED, now(), null,
                new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, 1000)));
        at(INSTANT.plusSeconds(1));
        AnalysisJob empty = job(7L, "empty");
        complete("ai-empty", success());
        job(8L, "other");
        mockMvc.perform(get("/api/v1/image-analyses").cookie(accessToken(7L)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("IMAGE-200-004"))
                .andExpect(jsonPath("$.data.analyses[*].analysisId").value(contains(empty.id(), mixed.id())))
                .andExpect(jsonPath("$.data.analyses[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.analyses[0].expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.analyses[0].images[0].recognitionStatus").value("UNRECOGNIZED"))
                .andExpect(jsonPath("$.data.analyses[0].images[0].itemCount").value(0))
                .andExpect(jsonPath("$.data.analyses[1].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.analyses[1].expiresAt").value(nullValue()))
                .andExpect(jsonPath("$.data.analyses[1].totalImageCount").value(3))
                .andExpect(jsonPath("$.data.analyses[1].completedImageCount").value(1))
                .andExpect(jsonPath("$.data.analyses[1].failedImageCount").value(1))
                .andExpect(jsonPath("$.data.analyses[1].images[*].imageObjectKey").value(contains("receipt", "failed", "pending")))
                .andExpect(jsonPath("$.data.analyses[1].images[*].status").value(contains("COMPLETED", "FAILED", "PROCESSING")))
                .andExpect(jsonPath("$.data.analyses[1].images[*].recognitionStatus").value(contains("NEEDS_REVIEW", null, null)))
                .andExpect(jsonPath("$.data.analyses[1].images[*].itemCount").value(contains(2, 0, null)))
                .andExpect(jsonPath("$..items").doesNotExist());
    }

    @Test
    void returnsAnEmptyListWithoutJobsAndRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/image-analyses").cookie(accessToken(9L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.analyses").isEmpty());
        mockMvc.perform(get("/api/v1/image-analyses"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
    }

    private AnalysisJob job(Long userId, String... objectKeys) {
        List<AnalysisImage> images = List.of(objectKeys).stream().map(key -> AnalysisImage.pending(key, "ab".repeat(32))).toList();
        AnalysisJob job = store.create(userId, "AUTO", images);
        for (AnalysisImage image : job.analysisImages()) {
            store.register(job.id(), userId, image.requestId(), "ai-" + image.objectKey());
        }
        return job;
    }

    private void complete(String analysisId, AnalysisCompletion completion) { store.complete(analysisId, completion); }

    private AnalysisCompletion success(AnalysisCompletionItem... items) {
        return new AnalysisCompletion(AnalysisStatus.COMPLETED, now(), new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of(items)), null);
    }

    private AnalysisCompletionItem item(String itemId, RecognitionStatus displayStatus) {
        return new AnalysisCompletionItem(itemId, displayStatus, "우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT, 1,
                new AnalysisCompletionItem.Weight(null, WeightUnit.NONE),
                new AnalysisCompletionItem.Expiration(LocalDate.parse("2026-10-20"), AnalysisExpirationDateType.USE_BY, null), List.of());
    }

    private void at(Instant instant) {
        when(clock.instant()).thenReturn(instant);
        when(clock.withZone(any())).thenReturn(Clock.fixed(instant, BusinessTime.ZONE));
    }

    private OffsetDateTime now() { return clock.instant().atZone(BusinessTime.ZONE).toOffsetDateTime(); }

    private Cookie accessToken(Long userId) { return new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)); }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({RedisTestSupport.Configuration.class, SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class, GlobalExceptionHandler.class,
            AnalysisController.class, AnalysisJobQueryService.class, AnalysisJobStore.class})
    static class WebConfiguration {
        @Bean
        static ConversionService conversionService() { return new ApplicationConversionService(); }
        @Bean
        Clock clock() { return mock(Clock.class); }
        @Bean
        ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean
        AnalysisJobSubmitService analysisJobSubmitService() { return mock(AnalysisJobSubmitService.class); }
    }
}
