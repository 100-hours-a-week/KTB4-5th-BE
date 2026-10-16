package com.dameokja.backend.user.presentation;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.user.application.UserProfile;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UserProfileWebTest extends UserSecurityWebTestSupport {
    @Autowired JwtProvider jwt;

    @Test
    void readsAuthenticatedUsersProfileWithSignedUrlAndStringIdsWithoutCsrf() throws Exception {
        when(userProfileService.getProfile(1L))
                .thenReturn(new UserProfile(1L, "dave", "dave@example.com", "images/profile/key", List.of(2L), 3));
        when(profileImageService.readUrl("images/profile/key")).thenReturn("https://example.com/photo?signature=test");
        mockMvc.perform(get("/api/v1/users/me").cookie(access()).param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("USER-200-003"))
                .andExpect(jsonPath("$.message").value("회원정보 조회 성공"))
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.nickname").value("dave"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://example.com/photo?signature=test"))
                .andExpect(jsonPath("$.data.activeRefrigeratorIds[0]").value("2"))
                .andExpect(jsonPath("$.data.cookingCount").value(3))
                .andExpect(jsonPath("$.data.profileImageKey").doesNotExist())
                .andExpect(jsonPath("$.data.email").value("dave@example.com"));
        verify(userProfileService).getProfile(1L);
    }

    @Test
    void unauthenticatedRequestCannotReadProfileOrSignImage() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
        verifyNoInteractions(userProfileService, profileImageService);
    }

    @Test
    void defaultImageAlsoGetsAReadUrlWithAnEmptyActiveRefrigeratorList() throws Exception {
        when(userProfileService.getProfile(1L)).thenReturn(new UserProfile(1L, "dave", null, "profiles/default.png", List.of(), 0));
        when(profileImageService.readUrl("profiles/default.png")).thenReturn("https://example.com/default?signature=test");
        mockMvc.perform(get("/api/v1/users/me").cookie(access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasKey("email")))
                .andExpect(jsonPath("$.data.email").value(nullValue()))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://example.com/default?signature=test"))
                .andExpect(jsonPath("$.data.activeRefrigeratorIds").isEmpty())
                .andExpect(jsonPath("$.data.cookingCount").value(0));
    }

    @Test
    void invalidTokenCannotReadProfileOrSignImage() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").cookie(new Cookie("accessToken", "invalid")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userProfileService, profileImageService);
    }

    @Test
    void missingUserRetainsExistingNotFoundError() throws Exception {
        when(userProfileService.getProfile(1L)).thenThrow(new CustomException(UserExceptionCode.USER_NOT_FOUND));
        mockMvc.perform(get("/api/v1/users/me").cookie(access()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER-404-001"));
        verifyNoInteractions(profileImageService);
    }

    @Test
    void withdrawnUserCannotObtainImageUrl() throws Exception {
        when(userProfileService.getProfile(1L)).thenThrow(new CustomException(UserExceptionCode.USER_NOT_ACTIVE));
        mockMvc.perform(get("/api/v1/users/me").cookie(access()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-403-001"));
        verifyNoInteractions(profileImageService);
    }

    private Cookie access() { return new Cookie("accessToken", jwt.createAccessToken(1L, UserRole.USER)); }
}
