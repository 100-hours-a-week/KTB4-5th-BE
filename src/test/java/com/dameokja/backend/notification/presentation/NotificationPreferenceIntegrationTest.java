package com.dameokja.backend.notification.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.support.MySqlDatabaseTest;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class NotificationPreferenceIntegrationTest extends MySqlDatabaseTest {
    private static final long USER = 985001L;
    private static final long OTHER = 985002L;
    private static final String SETTINGS = "/api/v1/notifications/settings";
    private static final String CHANGE = "/api/v1/notifications/settings";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationPreferenceService preferences;
    @Autowired ObjectMapper mapper;
    private Cookie access;
    private Cookie csrf;

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (?, '설정1','test'),(?, '설정2','test')", USER, OTHER);
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (?,'EXPIRATION',1),(?,'RECIPE',0),(?,'ANALYSIS',1),(?,'ANALYSIS',1)",
                USER, USER, USER, OTHER);
        access = new Cookie("accessToken", jwt.createAccessToken(USER, UserRole.USER));
        csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_preferences WHERE user_id IN (?,?)", USER, OTHER);
        jdbc.update("DELETE FROM users WHERE user_id IN (?,?)", USER, OTHER);
    }

    @Test
    void changesOnlyListedTypesOfCurrentUserAndReturnsAllSettings() throws Exception {
        JsonNode changed = change(items("ANALYSIS", "false"), 200);
        assertThat(changed.get("code").asText()).isEqualTo("NOTI-200-002");
        assertThat(enabled(changed.get("data"))).containsExactly(true, false, false);
        change(items("ANALYSIS", "false"), 200);
        assertThat(preferences.isAnalysisEnabled(USER)).isFalse();
        assertThat(preferences.isAnalysisEnabled(OTHER)).isTrue();
        JsonNode settings = mapper.readTree(mvc.perform(get(SETTINGS).cookie(access)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(settings.get("notificationPreferences").get(2).get("type").asText()).isEqualTo("ANALYSIS");
        assertThat(enabled(settings)).containsExactly(true, false, false);
    }

    @Test
    void changesSeveralTypesAtOnceWithoutTouchingOmittedType() throws Exception {
        JsonNode changed = change("{\"notificationPreferences\":[{\"type\":\"EXPIRATION\",\"isEnabled\":false},"
                + "{\"type\":\"RECIPE\",\"isEnabled\":true}]}", 200);
        assertThat(enabled(changed.get("data"))).containsExactly(false, true, true);
        assertThat(preferences.isAnalysisEnabled(USER)).isTrue();
    }

    @Test
    void requiresAuthenticationAndCsrfForMutation() throws Exception {
        mvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
        mvc.perform(patch(CHANGE).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(items("ANALYSIS", "false"))).andExpect(status().isUnauthorized());
        mvc.perform(patch(CHANGE).cookie(access).contentType(MediaType.APPLICATION_JSON)
                .content(items("ANALYSIS", "false"))).andExpect(status().isForbidden());
        assertThat(preferences.isAnalysisEnabled(USER)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"notificationPreferences\":[]}", "{\"notificationPreferences\":[null]}",
            "{\"notificationPreferences\":[{\"type\":\"ANALYSIS\",\"isEnabled\":null}]}",
            "{\"notificationPreferences\":[{\"type\":\"ANALYSIS\",\"isEnabled\":\"invalid\"}]}",
            "{\"notificationPreferences\":[{\"type\":\"UNKNOWN\",\"isEnabled\":false}]}",
            "{\"notificationPreferences\":[{\"isEnabled\":false}]}",
            "{\"notificationPreferences\":[{\"type\":\"ANALYSIS\",\"isEnabled\":false},{\"type\":\"ANALYSIS\",\"isEnabled\":true}]}"})
    void rejectsInvalidBodyWithoutChangingSettings(String body) throws Exception {
        change(body, 400);
        assertThat(preferences.isAnalysisEnabled(USER)).isTrue();
    }

    @Test
    void rollsBackEveryChangeWhenOneTypeHasNoStoredSetting() throws Exception {
        jdbc.update("DELETE FROM notification_preferences WHERE user_id=? AND type='ANALYSIS'", USER);
        change("{\"notificationPreferences\":[{\"type\":\"EXPIRATION\",\"isEnabled\":false},"
                + "{\"type\":\"ANALYSIS\",\"isEnabled\":false}]}", 404);
        assertThat(jdbc.queryForObject("SELECT is_enabled FROM notification_preferences WHERE user_id=? AND type='EXPIRATION'", Boolean.class, USER)).isTrue();
    }

    private JsonNode change(String body, int expectedStatus) throws Exception {
        String response = mvc.perform(patch(CHANGE).cookie(access, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private String items(String type, String enabled) {
        return "{\"notificationPreferences\":[{\"type\":\"" + type + "\",\"isEnabled\":" + enabled + "}]}";
    }

    private List<Boolean> enabled(JsonNode data) {
        List<Boolean> values = new ArrayList<>();
        data.get("notificationPreferences").forEach(item -> values.add(item.get("isEnabled").asBoolean()));
        return values;
    }
}
