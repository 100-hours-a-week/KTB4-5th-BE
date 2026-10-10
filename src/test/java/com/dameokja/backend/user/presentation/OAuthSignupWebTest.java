package com.dameokja.backend.user.presentation;

import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.application.OAuthSignupService;
import com.dameokja.backend.user.application.SignupResult;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ContextConfiguration(classes = OAuthSignupController.class)
class OAuthSignupWebTest extends UserSecurityWebTestSupport {
    @MockitoBean OAuthSignupService oauthSignup;

    @Test
    void anonymousSignupIgnoresStaleAccessCookieAndSetsServiceCookies() throws Exception {
        when(oauthSignup.signup("registration", "dave", false))
                .thenReturn(new SignupResult(new TokenPair("access", "refresh", 7L), List.of(3L)));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/users/oauth").cookie(csrf, new Cookie("registrationToken", "registration"),
                        new Cookie("accessToken", "invalid"))
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\" dave \",\"notificationSetting\":false}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("USER-201-002"))
                .andExpect(jsonPath("$.data.activeRefrigeratorIds[0]").value("3"))
                .andExpect(cookie().httpOnly("accessToken", true)).andExpect(cookie().secure("refreshToken", true))
                .andExpect(cookie().maxAge("registrationToken", 0))
                .andExpect(cookie().path("registrationToken", "/"))
                .andExpect(cookie().httpOnly("registrationToken", true))
                .andExpect(cookie().secure("registrationToken", true))
                .andExpect(cookie().value("XSRF-TOKEN", not(csrf.getValue())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nickname\":\"dave\"}", "{\"nickname\":\"x\",\"notificationSetting\":true}", "{\"nickname\":null,\"notificationSetting\":true}"})
    void rejectsMissingRequiredFieldsAndInvalidNickname(String body) throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/users/oauth").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(oauthSignup);
    }

    @Test
    void csrfFailurePreventsSignup() throws Exception {
        mockMvc.perform(post("/api/v1/users/oauth").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"dave\",\"notificationSetting\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        verifyNoInteractions(oauthSignup);
    }

    @Test
    void invalidRegistrationReturnsSpecifiedError() throws Exception {
        when(oauthSignup.signup(null, "dave", true)).thenThrow(new CustomException(UserExceptionCode.REGISTRATION_INVALID));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/users/oauth").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"dave\",\"notificationSetting\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("USER-400-001"))
                .andExpect(jsonPath("$.message").value("회원가입 인증이 유효하지 않습니다."));
    }

}
