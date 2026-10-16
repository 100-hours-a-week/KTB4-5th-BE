package com.dameokja.backend.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.support.MySqlDatabaseTest;
import com.dameokja.backend.user.domain.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.jpa.show-sql=false", "push.expiration-batch.enabled=false"})
@Import(NotificationStreamTimeoutIntegrationTest.ShortTimeout.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationStreamTimeoutIntegrationTest extends MySqlDatabaseTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (940001,'SSE종료','test')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (940001,'SSE종료','2026-10')");
        jdbc.update("INSERT INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES (940001,940001,1)");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM refrigerator_members WHERE user_id=940001");
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id=940001");
        jdbc.update("DELETE FROM users WHERE user_id=940001");
    }

    @Test
    void timeoutEndsTheHttpStreamAndNewConnectionStillWorks() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/v2/refrigerators/940001/notifications/stream"))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "text/event-stream")
                    .header("Cookie", "accessToken=" + jwt.createAccessToken(940001L, UserRole.USER)).GET().build();
            for (int connection = 0; connection < 2; connection++) {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("event:connected", "data:{}").doesNotContain("\"code\"");
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ShortTimeout {
        @Bean
        @Primary
        NotificationSseConnections shortTimeoutConnections(NotificationConnectionPresence presence) {
            return new NotificationSseConnections(() -> new SseEmitter(200L), presence);
        }
    }
}
