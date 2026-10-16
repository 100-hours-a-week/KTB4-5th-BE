package com.dameokja.backend.user.presentation;

import com.dameokja.backend.user.application.NicknameValidationResult;
import com.dameokja.backend.user.application.NicknameValidationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NicknameValidationWebTest extends UserSecurityWebTestSupport {
    private static final String URL = "/api/v1/users/nickname-validations";

    @Autowired NicknameValidationService nicknameValidationService;

    @BeforeEach
    void resetService() {
        reset(nicknameValidationService);
    }

    @Test
    void availableNicknameWithoutLoginReturnsValidResult() throws Exception {
        when(nicknameValidationService.validate("dave")).thenReturn(NicknameValidationResult.AVAILABLE);
        perform("{\"nickname\":\" da ve \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("USER-200-001"))
                .andExpect(jsonPath("$.message").value("사용 가능한 닉네임"))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.reason").value(nullValue()));
    }

    @Test
    void unavailableNicknameReturnsOkWithReason() throws Exception {
        when(nicknameValidationService.validate("dave")).thenReturn(NicknameValidationResult.DUPLICATE);
        perform("{\"nickname\":\"dave\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("USER-200-001"))
                .andExpect(jsonPath("$.message").value("중복된 닉네임입니다."))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.reason").value("닉네임 중복"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nickname\":null}"})
    void missingNicknameIsBadRequest(String body) throws Exception {
        perform(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        verifyNoInteractions(nicknameValidationService);
    }

    @Test
    void csrfFailurePreventsValidation() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"dave\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        verifyNoInteractions(nicknameValidationService);
    }

    private ResultActions perform(String body) throws Exception {
        Cookie csrf = csrf();
        return mockMvc.perform(post(URL).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
