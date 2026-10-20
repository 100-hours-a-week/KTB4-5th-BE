package com.dameokja.backend.notification.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.notification.infrastructure.NotificationSseConnections;
import com.dameokja.backend.support.MySqlDatabaseTest;
import com.dameokja.backend.user.domain.UserRole;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.jpa.show-sql=false",
                "push.dispatch.enabled=false", "push.expiration-batch.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationStreamConnectionReleaseIntegrationTest extends MySqlDatabaseTest {
    private static final long USER_ID = 960901L;
    private static final String STREAM = "/api/v2/refrigerators/960901/notifications/stream";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired HikariDataSource dataSource;
    @Autowired NotificationSseConnections connections;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (?, 'SSE반환', 'test')", USER_ID);
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (?, 'SSE반환', '2026-10')", USER_ID);
        jdbc.update("INSERT INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES (?, ?, 1)", USER_ID, USER_ID);
    }

    @AfterEach
    void cleanup() {
        connections.shutdown();
        jdbc.update("DELETE FROM refrigerator_members WHERE user_id=?", USER_ID);
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id=?", USER_ID);
        jdbc.update("DELETE FROM users WHERE user_id=?", USER_ID);
    }

    @Test
    void openStreamsReleaseDatabaseConnectionsAndAllowAnotherAuthenticatedRequest() throws Exception {
        String token = jwt.createAccessToken(USER_ID, UserRole.USER);
        try (HttpClient client = HttpClient.newHttpClient();
                InputStream first = openStream(client, token);
                InputStream second = openStream(client, token)) {
            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections())
                    .as("열린 SSE가 DB 연결을 계속 점유하지 않아야 한다").isZero();

            HttpResponse<String> response = client.send(request("/api/v1/users/me", token).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"userId\":\"960901\"");
            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    private InputStream openStream(HttpClient client, String token) throws Exception {
        HttpResponse<InputStream> response = client.send(request(STREAM, token)
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        BufferedReader events = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        assertThat(events.readLine()).isEqualTo("event:connected");
        return response.body();
    }

    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Cookie", "accessToken=" + token).GET();
    }
}
