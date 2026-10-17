package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRole;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.jpa.show-sql=false", "app.user.default-profile-image-key=profiles/default.png"})
// 전용 서버 컨텍스트와 DB 연결을 클래스 종료 시 반납한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "refrigerator.invitation.fixed-refrigerator-id=90000001")
class SharingApiIntegrationTest extends ServiceIntegrationTest {
    @LocalServerPort int port;
    @Autowired JwtProvider jwt;
    final ObjectMapper mapper = new ObjectMapper();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String auth;
    String csrf;

    @Test
    void frontendCanJoinWithTheSameConfiguredCodeAfterExpiration() throws Exception {
        User owner = user("Kakaotech");
        long refrigeratorId = 90000001L;
        jdbcTemplate.update("INSERT INTO refrigerators(refrigerator_id, name, expired_count_month) VALUES (?, ?, ?)",
                refrigeratorId, "Kakaotech", "2026-09");
        Refrigerator refrigerator = refrigeratorRepository.findById(refrigeratorId).orElseThrow();
        join(owner, refrigerator, true, true);
        Fixture joining = ownerFixture("Joining");
        String base = "/api/v1/refrigerators/" + refrigeratorId;
        login(owner.getId());
        String code = json(call("POST", base + "/invitations", "", 200)).at("/data/code").asText();
        clock.set("2026-09-17T03:06:00Z");
        JsonNode retrieved = json(call("GET", base + "/invitations", "", 200));
        assertThat(retrieved.at("/data/code").asText()).isEqualTo(code);
        assertThat(retrieved.at("/data/expiresAt").asText()).isEqualTo("2099-12-31T00:05:00+09:00");
        assertThat(json(call("POST", base + "/invitations", "", 200)).at("/data/code").asText()).isEqualTo(code);
        login(joining.userId());
        JsonNode joined = json(call("POST", "/api/v1/refrigerators/members", "{\"inviteCode\":\"" + code + "\"}", 201));
        assertThat(joined.at("/data/refrigeratorId").asText()).isEqualTo(Long.toString(refrigeratorId));
        assertThat(joined.at("/data/activeRefrigeratorIds/0").asText()).isEqualTo(Long.toString(refrigeratorId));
    }

    @Test
    void completeHttpFlowMatchesSpecificationAndRestoresPersonal() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture joining = ownerFixture("Joining");
        String base = "/api/v1/refrigerators/" + owner.refrigeratorId();
        login(owner.userId());
        JsonNode empty = json(call("GET", base + "/invitations", "", 200));
        assertThat(empty.get("data").isNull()).isTrue();
        String code = issueAndVerify(owner.userId(), base);
        login(joining.userId());
        JsonNode joined = json(call("POST", "/api/v1/refrigerators/members", "{\"inviteCode\":\"" + code + "\"}", 201));
        assertThat(joined.at("/data/refrigeratorId").isString()).isTrue();
        assertThat(joined.at("/data/activeRefrigeratorIds").isArray()).isTrue();
        assertThat(joined.at("/data/activeRefrigeratorIds/0").asText()).isEqualTo(owner.refrigeratorId().toString());
        JsonNode list = json(call("GET", base + "/members", "", 200));
        assertThat(list.at("/data/currentUserId").isString()).isTrue();
        assertThat(list.at("/data/currentUserId").asText()).isEqualTo(joining.userId().toString());
        assertThat(list.at("/data/memberNum").asInt()).isEqualTo(2);
        assertThat(list.at("/data/memberMax").asInt()).isEqualTo(150);
        assertThat(list.at("/data/members/0/role").asText()).isEqualTo("owner");
        assertThat(json(call("GET", base + "/members/stats", "", 200)).at("/data/memberNum").asInt()).isEqualTo(2);
        JsonNode left = json(call("DELETE", base + "/members/me", "", 200));
        assertThat(left.at("/data/activeRefrigeratorIds/0").asText()).isEqualTo(joining.refrigeratorId().toString());
        call("GET", base + "/members", "", 403);
    }

    @Test
    void kickReturnsEmpty204AndRejectsUnauthenticatedOrMissingCsrf() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture target = ownerFixture("Target");
        String base = "/api/v1/refrigerators/" + owner.refrigeratorId();
        login(owner.userId());
        String code = json(call("POST", base + "/invitations", "", 200)).at("/data/code").asText();
        login(target.userId());
        call("POST", "/api/v1/refrigerators/members", "{\"inviteCode\":\"" + code + "\"}", 201);
        login(owner.userId());
        assertThat(call("DELETE", base + "/members/" + target.userId(), "", 204).body()).isEmpty();
        csrf = null;
        JsonNode rejected = json(call("POST", base + "/invitations", "", 403));
        assertThat(rejected.get("code").asText()).isEqualTo("COMMON-403-CSRF-001");
        auth = null;
        call("GET", base + "/members", "", 401);
    }

    private String issueAndVerify(Long userId, String base) throws Exception {
        JsonNode issued = json(call("POST", base + "/invitations", "", 200));
        assertThat(issued.get("data").size()).isOne();
        HttpResponse<String> retrieved = call("GET", base + "/invitations", "", 200);
        assertThat(retrieved.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json(retrieved).at("/data/expiresAt").asText()).endsWith("+09:00");
        return issued.at("/data/code").asText();
    }

    private void login(Long id) throws Exception {
        auth = "accessToken=" + jwt.createAccessToken(id, UserRole.USER);
        csrf = null;
        csrf = call("GET", "/api/v1/auth/csrf", "", 204).headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> call(String method, String path, String body, int status) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        String cookie = auth == null ? "" : auth;
        if (csrf != null) {
            cookie += "; " + csrf;
            request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        }
        if (!cookie.isEmpty()) request.header("Cookie", cookie);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return response;
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }
}
