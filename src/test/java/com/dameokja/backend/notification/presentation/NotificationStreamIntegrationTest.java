package com.dameokja.backend.notification.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.BackendApplication;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.notification.application.NotificationService;
import com.dameokja.backend.notification.infrastructure.NotificationSseConnections;
import com.dameokja.backend.support.MySqlDatabaseTest;
import com.dameokja.backend.user.domain.UserRole;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.jpa.show-sql=false", "push.expiration-batch.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationStreamIntegrationTest extends MySqlDatabaseTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired NotificationService notifications;
    @Autowired NotificationSseConnections connections;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String STREAM = "/api/v2/refrigerators/950001/notifications/stream";

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (950001,'SSEA','test'),(950002,'SSEB','test')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (950001,'SSE','2026-10'),(950002,'비활성','2026-10')");
        jdbc.update("INSERT INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES (950001,950001,1),(950002,950001,1),(950001,950002,0)");
        jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id) VALUES (950001,'EXPIRED','만료','본문',950001)");
        jdbc.update("INSERT INTO notification_recipients(notification_id,user_id) VALUES (950001,950001),(950001,950002)");
    }

    @AfterEach
    void cleanup() {
        connections.shutdown();
        jdbc.update("DELETE FROM notification_recipients WHERE notification_id=950001");
        jdbc.update("DELETE FROM notifications WHERE notification_id=950001");
        jdbc.update("DELETE FROM refrigerator_members WHERE user_id IN (950001,950002)");
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id IN (950001,950002)");
        jdbc.update("DELETE FROM users WHERE user_id IN (950001,950002)");
    }

    @Test
    void realRedisReadSignalsReachAllOwnHttpConnectionsAndReconnectGetsCurrentState() throws Exception {
        try (Probe first = open(950001); Probe second = open(950001); Probe other = open(950002)) {
            notifications.readNotification(950001L, 950001L);
            first.expectChanged();
            second.expectChanged();
            assertThat(other.events.poll(200, TimeUnit.MILLISECONDS)).isNull();
            assertThat(jdbc.queryForObject("SELECT read_at FROM notification_recipients WHERE user_id=950002", Object.class)).isNull();
            jdbc.update("UPDATE notification_recipients SET read_at=NULL WHERE user_id=950001");
            notifications.readAllNotifications(950001L, 950001L);
            first.expectChanged();
            second.expectChanged();
            assertThat(other.events.poll(200, TimeUnit.MILLISECONDS)).isNull();
        }
        try (Probe reconnected = open(950001)) {
            HttpResponse<String> count = json("/api/v1/refrigerators/950001/notifications/unread-count", 950001);
            assertThat(count.statusCode()).isEqualTo(200);
            assertThat(count.body()).contains("\"unreadCount\":0");
            HttpResponse<String> list = json("/api/v1/refrigerators/950001/notifications", 950001);
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.body()).contains("\"notificationId\":\"950001\"").doesNotContain("\"readAt\":null");
            connections.heartbeat();
            assertThat(reconnected.next()).contains(":heartbeat");
        }
    }

    @Test
    void aWriteOnOneServerReachesSseConnectionsOnBothServers() throws Exception {
        try (ConfigurableApplicationContext remoteServer = startSecondServer()) {
            int remotePort = ((WebServerApplicationContext) remoteServer).getWebServer().getPort();
            try (Probe local = open(950001); Probe remote = openAt(remotePort, STREAM, 950001)) {
                notifications.readNotification(950001L, 950001L);
                local.expectChanged();
                remote.expectChanged();
            }
        }
    }

    private ConfigurableApplicationContext startSecondServer() {
        Map<String, Object> properties = experimentProperties();
        properties.put("spring.config.import", "");
        properties.put("spring.jpa.show-sql", "false");
        properties.put("push.expiration-batch.enabled", "false");
        properties.put("server.port", "0");
        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(BackendApplication.class).run(arguments);
    }

    @Test
    void cookieAuthenticationAndActiveFridgeAuthorizationAreRequired() throws Exception {
        expectStreamError(STREAM, 0, 401, "GLOBAL-401-001");
        expectStreamError(STREAM.replace("950001", "950002"), 950001, 403, "REFRIGERATOR-403-001");
        expectStreamError(STREAM.replace("950001", "999999"), 950001, 404, "REFRIGERATOR-404-001");
        jdbc.update("UPDATE refrigerators SET deleted_at=NOW() WHERE refrigerator_id=950001");
        expectStreamError(STREAM, 950001, 410, "REFRIGERATOR-410-001");
    }

    @Test
    void clientUserIdCannotOverrideAuthenticatedConnectionOwner() throws Exception {
        try (Probe own = openPath(STREAM + "?userId=950002", 950001)) {
            connections.notifyChanged(950002L, 111L);
            connections.notifyChanged(950001L, 950001L);
            own.expectChanged();
            assertThat(own.events.poll(200, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void aSignalForAnotherFridgeIsNotSentToTheHttpConnection() throws Exception {
        try (Probe own = open(950001)) {
            connections.notifyChanged(950001L, 950002L);
            assertThat(own.events.poll(200, TimeUnit.MILLISECONDS)).isNull();

            connections.notifyChanged(950001L, 950001L);

            own.expectChanged();
        }
    }

    @Test
    void oldJsonPollingRemainsCompatibleWithoutAcceptHeader() throws Exception {
        HttpRequest request = request("/api/v1/refrigerators/950001/notifications/stream", 950001).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        assertThat(response.body()).contains("\"notificationId\":\"950001\"");
    }

    private Probe open(long userId) throws Exception { return openPath(STREAM, userId); }

    private Probe openPath(String path, long userId) throws Exception { return openAt(port, path, userId); }

    private Probe openAt(int serverPort, String path, long userId) throws Exception {
        HttpResponse<InputStream> response = client.send(requestAt(serverPort, path, userId)
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("text/event-stream");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("X-Accel-Buffering").orElseThrow()).isEqualTo("no");
        Probe probe = new Probe(response.body());
        assertThat(probe.next()).contains("event:connected", "retry:3000", "data:{}");
        return probe;
    }

    private HttpResponse<String> json(String path, long userId) throws Exception {
        return client.send(request(path, userId).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void expectStreamError(String path, long userId, int status, String code) throws Exception {
        HttpRequest streamRequest = request(path, userId).header("Accept", "text/event-stream").build();
        HttpResponse<String> response = client.send(streamRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }

    private HttpRequest.Builder request(String path, long userId) { return requestAt(port, path, userId); }

    private HttpRequest.Builder requestAt(int serverPort, String path, long userId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
                .timeout(Duration.ofSeconds(5)).GET();
        if (userId > 0) builder.header("Cookie", "accessToken=" + jwt.createAccessToken(userId, UserRole.USER));
        return builder;
    }

    private static class Probe implements AutoCloseable {
        private final InputStream input;
        private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        private final Thread reader;

        Probe(InputStream input) {
            this.input = input;
            reader = Thread.ofVirtual().start(this::read);
        }

        private void read() {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder event = new StringBuilder();
                for (String line; (line = lines.readLine()) != null;) {
                    if (line.isEmpty()) {
                        events.add(event.toString());
                        event.setLength(0);
                    } else {
                        event.append(line).append('\n');
                    }
                }
            } catch (java.io.IOException ignored) {
                // 클라이언트가 닫은 스트림은 정상 종료다.
            }
        }

        String next() throws InterruptedException {
            String event = events.poll(5, TimeUnit.SECONDS);
            assertThat(event).as("SSE event within 5 seconds").isNotNull();
            return event;
        }

        void expectChanged() throws InterruptedException {
            assertThat(next()).contains("event:notifications-changed", "data:{\"refrigeratorId\":\"950001\"}").doesNotContain("userId");
        }

        @Override
        public void close() throws Exception {
            input.close();
            reader.join(Duration.ofSeconds(1));
        }
    }
}
