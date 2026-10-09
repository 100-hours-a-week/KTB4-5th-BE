package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.AnalysisJobSubmitService;
import com.dameokja.backend.analysis.application.AnalysisJobQueryService;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.auth.presentation.CsrfController;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(AnalysisJobSubmitWebTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {"jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d", "auth.cookie.secure=true", "analysis.job-ttl=1h"})
class AnalysisJobSubmitWebTest {
    private static final String BODY = "{\"imageObjectKeys\":[\"first\",\"second\"]}";
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtProvider jwtProvider;
    @Autowired AiAnalysisClient aiAnalysisClient;
    @Autowired ImageUploadMetadataStore imageUploadMetadataStore;
    @Autowired AnalysisJobStore analysisJobStore;
    @Autowired ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        reset(aiAnalysisClient);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        imageUploadMetadataStore.save(7L, ImageUploadPurpose.ANALYSIS, "first", "ab".repeat(32));
        imageUploadMetadataStore.save(7L, ImageUploadPurpose.ANALYSIS, "second", "cd".repeat(32));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"AUTO", "RECEIPT"})
    void returnsAcceptedWithBackendJobAfterRegisteringEveryImage(String inputHint) throws Exception {
        when(aiAnalysisClient.submit(any())).thenReturn(accepted("ai-first"), accepted("ai-second"));
        Cookie cookie = csrf();
        String body = inputHint == null ? BODY : BODY.replace("}", ",\"inputHint\":\"" + inputHint + "\"}");
        String response = mockMvc.perform(post("/api/v1/image-analyses").cookie(cookie, accessToken())
                        .header("X-XSRF-TOKEN", cookie.getValue()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("IMAGE-202-001"))
                .andExpect(jsonPath("$.message").value("이미지 인식 시작"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-10-08T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-08T11:00:00+09:00"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1000)).andReturn().getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(response).path("data");
        assertThat(jsonNode.size()).isEqualTo(5);
        AnalysisJob analysisJob = analysisJobStore.findOwned(jsonNode.path("analysisId").asString(), 7L);
        assertThat(analysisJob.id()).isNotIn("ai-first", "ai-second");
        assertThat(analysisJob.analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsExactly("ai-first", "ai-second");
        ArgumentCaptor<AiImageAnalysisSubmitRequest> argumentCaptor = ArgumentCaptor.forClass(AiImageAnalysisSubmitRequest.class);
        verify(aiAnalysisClient, times(2)).submit(argumentCaptor.capture());
        assertThat(argumentCaptor.getAllValues()).extracting(AiImageAnalysisSubmitRequest::inputHint).containsOnly(inputHint == null ? "AUTO" : inputHint);
        assertThat(argumentCaptor.getAllValues()).extracting(aiImageAnalysisSubmitRequest -> aiImageAnalysisSubmitRequest.image().sha256()).containsExactly("ab".repeat(32), "cd".repeat(32));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "{\"imageObjectKeys\":null}", "{\"imageObjectKeys\":[]}",
            "{\"imageObjectKeys\":[null]}", "{\"imageObjectKeys\":[\" \" ]}", "{\"imageObjectKeys\":\"first\"}",
            "{\"imageObjectKeys\":[\"first\"],\"inputHint\":\"UNKNOWN\"}"})
    void rejectsMalformedRequestsBeforeCallingAi(String body) throws Exception {
        Cookie cookie = csrf();
        mockMvc.perform(post("/api/v1/image-analyses").cookie(cookie, accessToken()).header("X-XSRF-TOKEN", cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        verifyNoInteractions(aiAnalysisClient);
    }

    @ParameterizedTest
    @CsvSource({"LOGIN,401,GLOBAL-401-001", "CSRF,403,COMMON-403-CSRF-001", "MISMATCH,403,COMMON-403-CSRF-001"})
    void rejectsMissingLoginOrInvalidCsrf(String missing, int status, String code) throws Exception {
        Cookie cookie = csrf();
        Cookie[] cookies = missing.equals("LOGIN") ? new Cookie[]{cookie} : missing.equals("CSRF") ? new Cookie[]{accessToken()} : new Cookie[]{cookie, accessToken()};
        mockMvc.perform(post("/api/v1/image-analyses").cookie(cookies).header("X-XSRF-TOKEN", missing.equals("MISMATCH") ? "wrong" : cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
        verifyNoInteractions(aiAnalysisClient);
    }

    @Test
    void rejectsAnotherUsersUploadBeforeCallingAi() throws Exception {
        imageUploadMetadataStore.save(8L, ImageUploadPurpose.ANALYSIS, "second", "cd".repeat(32));
        Cookie cookie = csrf();
        mockMvc.perform(post("/api/v1/image-analyses").cookie(cookie, accessToken()).header("X-XSRF-TOKEN", cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMAGE-422-001"));
        verifyNoInteractions(aiAnalysisClient);
    }

    @ParameterizedTest
    @CsvSource({"400,400,GLOBAL-400-001", "429,429,IMAGE-429-001", "503,503,IMAGE-503-001"})
    void mapsAiAcceptanceFailuresToHttpErrors(int upstreamStatus, int status, String code) throws Exception {
        when(aiAnalysisClient.submit(any())).thenThrow(new RestClientResponseException("rejected", upstreamStatus, "error", null, null, null));
        Cookie cookie = csrf();
        mockMvc.perform(post("/api/v1/image-analyses").cookie(cookie, accessToken()).header("X-XSRF-TOKEN", cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    private Cookie csrf() throws Exception { return mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN"); }
    private Cookie accessToken() { return new Cookie("accessToken", jwtProvider.createAccessToken(7L, UserRole.USER)); }
    private AiImageAnalysisSubmitResponse accepted(String analysisId) {
        return new AiImageAnalysisSubmitResponse(analysisId, "QUEUED", Instant.parse("2026-10-08T01:00:00Z").atZone(BusinessTime.ZONE).toOffsetDateTime(), 1000);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class, CsrfController.class, GlobalExceptionHandler.class,
            AnalysisController.class, AnalysisJobSubmitService.class, ImageAnalysisInputService.class, ImageUploadMetadataStore.class, AnalysisJobStore.class})
    static class WebConfiguration {
        @Bean
        static org.springframework.core.convert.ConversionService conversionService() { return new org.springframework.boot.convert.ApplicationConversionService(); }
        @Bean
        AnalysisJobQueryService analysisJobQueryService() { return mock(AnalysisJobQueryService.class); }
        @Bean
        Clock clock() { return Clock.fixed(Instant.parse("2026-10-08T01:00:00Z"), BusinessTime.ZONE); }
        @Bean
        ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean
        AiAnalysisClient aiAnalysisClient() { return mock(AiAnalysisClient.class); }
    }
}
