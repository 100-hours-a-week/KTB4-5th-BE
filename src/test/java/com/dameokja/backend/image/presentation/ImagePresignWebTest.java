package com.dameokja.backend.image.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.AuthPrincipal;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.image.application.ImagePresignService;
import com.dameokja.backend.image.application.ImageUploadResult;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(ImagePresignWebTest.WebConfiguration.class)
@WebAppConfiguration
class ImagePresignWebTest {
    private static final String REQUEST = "{\"purpose\":\"ANALYSIS\",\"contentType\":\"image/png\",\"byteSize\":1,\"sha256\":\"" + "ab".repeat(32) + "\"}";
    @Autowired WebApplicationContext context;
    @Autowired ImagePresignService service;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        org.mockito.Mockito.reset(service);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void returnsObjectKeyAndUploadContractForAuthenticatedUser() throws Exception {
        ImageUploadResult result = new ImageUploadResult("test-images/analysis/7/test.png", "https://storage.example.com/signed-upload", "PUT",
                Map.of("Content-Type", "image/png", "x-amz-checksum-sha256", "checksum"), OffsetDateTime.parse("2026-10-05T12:00:00+09:00"));
        org.mockito.Mockito.when(service.issue(7L, ImageUploadPurpose.ANALYSIS, "image/png", 1L, "ab".repeat(32))).thenReturn(result);
        mvc.perform(post("/api/v1/image/presigned-url").with(authentication()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("IMAGE-200-001"))
                .andExpect(jsonPath("$.data.objectKey").value(result.objectKey())).andExpect(jsonPath("$.data.method").value("PUT"))
                .andExpect(jsonPath("$.data.headers.Content-Type").value("image/png"))
                .andExpect(jsonPath("$.data.uploadUrl").value(result.uploadUrl())).andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"purpose", "contentType", "byteSize", "sha256"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        ObjectNode invalid = (ObjectNode) JsonMapper.builder().build().readTree(REQUEST);
        invalid.remove(field);
        request(invalid.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"purpose,UNKNOWN", "contentType,''", "byteSize,0", "byteSize,-1", "sha256,ABC", "sha256,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "sha256,''"})
    void rejectsInvalidRequiredFields(String field, String value) throws Exception {
        ObjectNode invalid = (ObjectNode) JsonMapper.builder().build().readTree(REQUEST);
        if (field.equals("byteSize")) {
            invalid.put(field, Long.parseLong(value));
        } else {
            invalid.put(field, value);
        }
        request(invalid.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "9223372036854775808"})
    void rejectsFractionalOrOverflowingFileSize(String size) throws Exception {
        request(REQUEST.replace("\"byteSize\":1", "\"byteSize\":" + size)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void returnsGlobalServerErrorWithoutCredentialDetails() throws Exception {
        org.mockito.Mockito.when(service.issue(7L, ImageUploadPurpose.ANALYSIS, "image/png", 1L, "ab".repeat(32)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("test credential failure"));
        request(REQUEST).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.message").value("서버에서 요청을 처리하지 못했습니다."));
    }

    @Test
    void requiresAuthenticationAndCsrfBeforeIssuingUrl() throws Exception {
        mvc.perform(post("/api/v1/image/presigned-url").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
        mvc.perform(post("/api/v1/image/presigned-url").with(authentication()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        mvc.perform(post("/api/v1/image/presigned-url").with(authentication()).with(csrf().useInvalidToken())
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private org.springframework.test.web.servlet.ResultActions request(String body) throws Exception {
        return mvc.perform(post("/api/v1/image/presigned-url").with(authentication()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authentication() {
        UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(new AuthPrincipal(7L), null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(principal);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({ImageController.class, GlobalExceptionHandler.class, SecurityConfig.class, SecurityErrorHandler.class})
    static class WebConfiguration {
        @Bean ImagePresignService service() { return mock(ImagePresignService.class); }
        @Bean JwtProvider jwtProvider() { return mock(JwtProvider.class); }
        @Bean tools.jackson.databind.ObjectMapper objectMapper() { return tools.jackson.databind.json.JsonMapper.builder().build(); }
    }
}
