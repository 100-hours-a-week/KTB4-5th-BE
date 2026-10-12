package com.dameokja.backend.ingredient.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.support.MySqlDatabaseTest;
import com.dameokja.backend.user.domain.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

// QA가 보고한 실제 요청/응답(500, GLOBAL-500-001)을 그대로 재현하기 위한 임시 테스트.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.jpa.show-sql=false"})
class ReproIngredientCreateHttpTest extends MySqlDatabaseTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String cookie;
    private String csrfCookie;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (5,'qa유저','test')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (5,'qa냉장고','2026-09')");
        jdbc.update("INSERT INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES (5,5,1)");
        cookie = "accessToken=" + jwt.createAccessToken(5L, UserRole.USER);
        HttpResponse<String> csrf = call("GET", "/api/v1/auth/csrf", null, -1);
        csrfCookie = csrf.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow().split(";", 2)[0];
    }

    @Test
    void reproducesReportedPayloadOnRefrigerator5() throws Exception {
        String body = """
                {
                  "items": [
                    {
                      "name": "왜안되징",
                      "category": "OTHER",
                      "storageType": "FROZEN",
                      "measureType": "COUNT",
                      "quantity": 1,
                      "weightValue": null,
                      "weightUnit": "NONE",
                      "expirationDate": "2026-09-27",
                      "registrationSource": "DIRECT"
                    }
                  ]
                }
                """;
        HttpResponse<String> response = call("POST", "/api/v1/refrigerators/5/ingredients", body, -1);
        System.out.println("STATUS=" + response.statusCode());
        System.out.println("BODY=" + response.body());
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> call(String method, String path, String body, int expectedStatus) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, publisher);
        String cookies = cookie == null ? "" : cookie;
        if (csrfCookie != null) {
            cookies += (cookies.isEmpty() ? "" : "; ") + csrfCookie;
            request.header("X-XSRF-TOKEN", csrfCookie.substring("XSRF-TOKEN=".length()));
        }
        if (!cookies.isEmpty()) request.header("Cookie", cookies);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (expectedStatus >= 0) {
            assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(expectedStatus);
        }
        return response;
    }
}
