package com.dameokja.backend.refrigerator.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.support.MySqlDatabaseTest;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF"
})
@ActiveProfiles("loadtest")
class RefrigeratorReadSqlCountIntegrationTest extends MySqlDatabaseTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private String cookie;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (980001,'SQL검증','p')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (980001,'SQL검증','2026-10')");
        jdbc.update("INSERT INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES (980001,980001,1)");
        for (int i = 0; i < 20; i++) {
            jdbc.update("""
                    INSERT INTO ingredients(refrigerator_id,name,category,quantity,expiration_date,registration_source)
                    VALUES (980001,?,'OTHER',1,'2026-10-10','DIRECT')
                    """, "재료" + i);
        }
        cookie = "accessToken=" + jwt.createAccessToken(980001L, UserRole.USER);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM ingredients WHERE refrigerator_id=980001");
        jdbc.update("DELETE FROM refrigerator_members WHERE user_id=980001");
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id=980001");
        jdbc.update("DELETE FROM users WHERE user_id=980001");
    }

    @Test
    void successfulHttpListsExecuteThreeStatementsEach() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        HttpResponse<String> inventory = get("ingredients?size=10&sort=EXPIRATION_ASC");
        assertThat(inventory.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(inventory.body()).path("data").path("ingredients").size()).isEqualTo(10);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);

        statistics.clear();
        HttpResponse<String> notifications = get("notifications?type=ALL");
        assertThat(notifications.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(notifications.body()).path("data").path("notifications").size()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    private HttpResponse<String> get(String suffix) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/refrigerators/980001/" + suffix))
                .header("Cookie", cookie).timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
