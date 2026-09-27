package com.dameokja.backend.notification.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.support.MySqlDatabaseTest;
import com.dameokja.backend.user.domain.UserRole;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.jpa.show-sql=false"})
class NotificationApiIntegrationTest extends MySqlDatabaseTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String cookie;
    private String base;
    private String csrfCookie;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (910001,'알림테스트1','test'),(910002,'알림테스트2','test')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (910001,'알림테스트1','2026-09'),(910002,'알림테스트2','2026-09')");
        jdbc.update("INSERT INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES (910001,910001,1),(910002,910001,1),(910001,910002,0)");
        cookie = "accessToken=" + jwt.createAccessToken(910001L, UserRole.USER);
        base = "/api/v1/refrigerators/910001/notifications";
        HttpResponse<String> csrf = call("GET", "/api/v1/auth/csrf", 204);
        csrfCookie = csrf.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow().split(";", 2)[0];
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_recipients WHERE user_id IN (910001,910002)");
        jdbc.update("DELETE FROM notifications WHERE refrigerator_id IN (910001,910002)");
        jdbc.update("DELETE FROM refrigerator_members WHERE user_id IN (910001,910002)");
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id IN (910001,910002)");
        jdbc.update("DELETE FROM users WHERE user_id IN (910001,910002)");
    }

    @Test
    void emptyInboxReturnsEmptyListNullLatestAndZeroCount() throws Exception {
        JsonNode data = data(call("GET", base, 200));
        assertThat(data.get("notifications").size()).isZero();
        assertThat(data.get("hasNext").asBoolean()).isFalse();
        assertThat(data.get("nextCursor").isNull()).isTrue();
        assertThat(data(call("GET", base + "/stream", 200)).get("notificationId").isNull()).isTrue();
        assertThat(data(call("GET", base + "/unread-count", 200)).get("unreadCount").asInt()).isZero();
    }

    @Test
    void pollingReturnsSameLatestUntilNewOwnNotificationArrives() throws Exception {
        notification(1, 910001, 910001);
        HttpResponse<String> first = call("GET", base + "/stream", 200);
        assertThat(first.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(data(first).get("notificationId").asText()).isEqualTo("1");
        assertThat(data(call("GET", base + "/stream", 200))).isEqualTo(data(first));
        notification(2, 910001, 910002);
        notification(3, 910002, 910001);
        assertThat(data(call("GET", base + "/stream", 200)).get("notificationId").asText()).isEqualTo("1");
        notification(4, 910001, 910001);
        assertThat(data(call("GET", base + "/stream", 200)).get("notificationId").asText()).isEqualTo("4");
        JsonNode list = data(call("GET", base, 200)).get("notifications");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("notificationId").asText()).isEqualTo("4");
        assertThat(list.get(1).get("notificationId").asText()).isEqualTo("1");
        assertThat(data(call("GET", base + "/unread-count", 200)).get("unreadCount").asInt()).isEqualTo(2);
    }

    @Test
    void pagesInStableOrderWithoutDuplicatesAndStopsAt99() throws Exception {
        for (int id = 1; id <= 105; id++) notification(id, 910001, 910001);
        List<Long> ids = new ArrayList<>();
        String path = base;
        for (int page = 0; page < 10; page++) {
            JsonNode data = data(call("GET", path, 200));
            assertThat(data.get("notifications").size()).isEqualTo(page < 9 ? 10 : 9);
            for (JsonNode item : data.get("notifications")) ids.add(item.get("notificationId").asLong());
            assertThat(data.get("hasNext").asBoolean()).isEqualTo(page < 9);
            if (page < 9) path = base + "?cursor=" + URLEncoder.encode(data.get("nextCursor").asText(), StandardCharsets.UTF_8);
            else assertThat(data.get("nextCursor").isNull()).isTrue();
        }
        assertThat(ids).hasSize(99).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ids.getFirst()).isEqualTo(105L);
        assertThat(ids.getLast()).isEqualTo(7L);
    }

    @Test
    void singleReadPersistsAndFiltersAndRepeatedReadPreservesTimestamp() throws Exception {
        notification(1, 910001, 910001);
        notification(2, 910001, 910001);
        assertThat(call("PATCH", "/api/v1/notifications/1/read", 204).body()).isEmpty();
        Object readAt = jdbc.queryForObject("SELECT read_at FROM notification_recipients WHERE notification_id=1", Object.class);
        assertThat(readAt).isNotNull();
        call("PATCH", "/api/v1/notifications/1/read", 204);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification_recipients WHERE notification_id=1", Object.class)).isEqualTo(readAt);
        assertThat(data(call("GET", base + "?type=READ", 200)).get("notifications").get(0).get("notificationId").asText()).isEqualTo("1");
        assertThat(data(call("GET", base + "?type=UNREAD", 200)).get("notifications").size()).isEqualTo(1);
        HttpResponse<String> count = call("GET", base + "/unread-count", 200);
        assertThat(data(count).get("unreadCount").asInt()).isEqualTo(1);
        assertThat(count.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void bulkReadChangesOnlyOwnActiveFridgeRecipients() throws Exception {
        notification(1, 910001, 910001);
        notification(2, 910001, 910002);
        notification(3, 910002, 910001);
        jdbc.update("INSERT INTO notification_recipients(notification_id,user_id) VALUES (1,910002)");
        assertThat(call("PATCH", base + "/read-all", 204).body()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipients WHERE read_at IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(data(call("GET", base + "/unread-count", 200)).get("unreadCount").asInt()).isZero();
        call("PATCH", base + "/read-all", 204);
    }

    @Test
    void rejectsOtherRecipientsInactiveFridgeAndMissingNotification() throws Exception {
        notification(1, 910001, 910002);
        notification(2, 910002, 910001);
        call("PATCH", "/api/v1/notifications/1/read", 403);
        call("PATCH", "/api/v1/notifications/2/read", 403);
        call("PATCH", "/api/v1/notifications/999999/read", 404);
        for (String suffix : List.of("", "/stream", "/unread-count")) {
            call("GET", "/api/v1/refrigerators/910002/notifications" + suffix, 403);
        }
        call("PATCH", "/api/v1/refrigerators/910002/notifications/read-all", 403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipients WHERE read_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void rejectsMalformedCursorAndFilter() throws Exception {
        call("GET", base + "?cursor=invalid", 400);
        call("GET", base + "?type=INVALID", 400);
    }

    @Test
    void requiresAuthenticationForAllFiveEndpoints() throws Exception {
        cookie = null;
        for (String suffix : List.of("", "/stream", "/unread-count")) call("GET", base + suffix, 401);
        call("PATCH", base + "/read-all", 401);
        call("PATCH", "/api/v1/notifications/1/read", 401);
    }

    @Test
    void rejectsReadMutationWithoutCsrfToken() throws Exception {
        notification(1, 910001, 910001);
        csrfCookie = null;
        call("PATCH", "/api/v1/notifications/1/read", 403);
        call("PATCH", base + "/read-all", 403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipients WHERE read_at IS NOT NULL", Integer.class)).isZero();
    }

    private void notification(long id, long fridge, long user) {
        jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id,created_at,updated_at) "
                + "VALUES (?,'EXPIRED','만료 알림','테스트',?,'2026-09-24 10:00:00','2026-09-24 10:00:00')", id, fridge);
        jdbc.update("INSERT INTO notification_recipients(notification_id,user_id) VALUES (?,?)", id, user);
    }

    private HttpResponse<String> call(String method, String path, int expectedStatus) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody());
        String cookies = cookie == null ? "" : cookie;
        if (csrfCookie != null) {
            cookies += (cookies.isEmpty() ? "" : "; ") + csrfCookie;
            request.header("X-XSRF-TOKEN", csrfCookie.substring("XSRF-TOKEN=".length()));
        }
        if (!cookies.isEmpty()) request.header("Cookie", cookies);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(expectedStatus);
        return response;
    }

    private JsonNode data(HttpResponse<String> response) {
        return mapper.readTree(response.body()).get("data");
    }
}
