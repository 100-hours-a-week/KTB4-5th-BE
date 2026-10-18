package com.dameokja.backend.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.UserRole;
import com.dameokja.backend.user.domain.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class UserWithdrawalContractIntegrationTest extends ServiceIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwt;

    @Test
    void openApiExposesWithdrawalWithoutRequestBodyOrSuccessPayload() throws Exception {
        String operation = "$.paths['/api/v1/users/me'].delete";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".summary").value("회원 탈퇴"))
                .andExpect(jsonPath(operation + ".requestBody").doesNotExist())
                .andExpect(jsonPath(operation + ".parameters").doesNotExist())
                .andExpect(jsonPath(operation + ".responses['204']").exists())
                .andExpect(jsonPath(operation + ".responses['204'].content").doesNotExist())
                .andExpect(jsonPath(operation + ".responses['401']").exists())
                .andExpect(jsonPath(operation + ".responses['403']").exists());
    }

    @Test
    void httpWithdrawalCommitsUserAndRefrigeratorLifecycleAndRejectsOldAccess() throws Exception {
        Fixture owner = ownerFixture("User1");
        Cookie access = new Cookie("accessToken", jwt.createAccessToken(owner.userId(), UserRole.USER));
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        mockMvc.perform(delete("/api/v1/users/me").cookie(access, csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(userRepository.findById(owner.userId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.WITHDRAWN);
        assertThat(refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow().getDeletedAt())
                .isNotNull();
        mockMvc.perform(get("/api/v1/users/me").cookie(access))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER-403-001"));
    }
}
