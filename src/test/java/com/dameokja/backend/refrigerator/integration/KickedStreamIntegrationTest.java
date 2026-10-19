package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.BackendApplication;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.UserRole;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.jpa.show-sql=false", "push.dispatch.enabled=false",
                "push.expiration-batch.enabled=false", "app.user.default-profile-image-key=profiles/default.png"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KickedStreamIntegrationTest extends ServiceIntegrationTest {
    @LocalServerPort int port;
    @Autowired MembershipService memberships;
    @Autowired InvitationService invitations;
    @Autowired JwtProvider jwt;
    @Autowired PlatformTransactionManager transactions;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void committedKickReachesAllTargetStreamsAcrossServersWithEmptyData() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture target = joinedMember(owner);
        Fixture bystander = ownerFixture("Bystander");
        try (ConfigurableApplicationContext remote = secondServer()) {
            int remotePort = ((WebServerApplicationContext) remote).getWebServer().getPort();
            try (Probe first = open(port, target, owner); Probe second = open(port, target, owner);
                    Probe otherServer = open(remotePort, target, owner); Probe otherUser = open(port, bystander, bystander)) {
                memberships.kick(owner.userId(), owner.refrigeratorId(), target.userId());
                assertThat(first.next()).isEqualTo("event:kicked\ndata:{}\n");
                assertThat(second.next()).isEqualTo("event:kicked\ndata:{}\n");
                assertThat(otherServer.next()).isEqualTo("event:kicked\ndata:{}\n");
                otherUser.expectNoEvent();
                assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(target.userId())).contains(target.refrigeratorId());
                assertThat(getStatus(target, "/api/v2/refrigerators/" + owner.refrigeratorId() + "/notifications/stream")).isEqualTo(403);
                assertThat(getStatus(target, "/api/v1/refrigerators/current")).isEqualTo(200);
            }
        }
    }

    @Test
    void rollbackAndRejectedKickNeverSendKickedOrChangeTheActiveFridge() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture target = joinedMember(owner);
        try (Probe stream = open(port, target, owner)) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                memberships.kick(owner.userId(), owner.refrigeratorId(), target.userId());
                status.setRollbackOnly();
            });
            assertThatThrownBy(() -> memberships.kick(target.userId(), owner.refrigeratorId(), owner.userId()))
                    .isInstanceOf(CustomException.class);
            stream.expectNoEvent();
            assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(target.userId())).contains(owner.refrigeratorId());
        }
    }

    private Fixture joinedMember(Fixture owner) {
        Fixture member = ownerFixture("Target");
        memberships.join(member.userId(), invitations.issueCode(owner.userId(), owner.refrigeratorId()).code());
        return member;
    }

    private ConfigurableApplicationContext secondServer() {
        var properties = experimentProperties();
        properties.put("spring.config.import", "");
        properties.put("spring.jpa.show-sql", "false");
        properties.put("push.dispatch.enabled", "false");
        properties.put("push.expiration-batch.enabled", "false");
        properties.put("server.port", "0");
        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(BackendApplication.class)
                .initializers(context -> context.getBeanFactory().registerSingleton("testClock", clock)).run(arguments);
    }

    private Probe open(int serverPort, Fixture user, Fixture refrigerator) throws Exception {
        HttpRequest request = request(serverPort, user, "/api/v2/refrigerators/" + refrigerator.refrigeratorId() + "/notifications/stream")
                .header("Accept", "text/event-stream").build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        Probe probe = new Probe(response.body());
        assertThat(probe.next()).contains("event:connected", "data:{}");
        return probe;
    }

    private int getStatus(Fixture user, String path) throws Exception {
        return client.send(request(port, user, path).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private HttpRequest.Builder request(int serverPort, Fixture user, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path)).timeout(Duration.ofSeconds(5))
                .header("Cookie", "accessToken=" + jwt.createAccessToken(user.userId(), UserRole.USER)).GET();
    }

    private static final class Probe implements AutoCloseable {
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
                        recordEvent(event.toString());
                        event.setLength(0);
                    } else {
                        event.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // 테스트 클라이언트의 스트림 닫기는 정상 종료다.
            }
        }

        private void recordEvent(String event) {
            if (!event.startsWith(":")) {
                events.add(event);
            }
        }

        String next() throws InterruptedException {
            String event = events.poll(5, TimeUnit.SECONDS);
            assertThat(event).as("SSE event within 5 seconds").isNotNull();
            return event;
        }

        void expectNoEvent() throws InterruptedException {
            assertThat(events.poll(200, TimeUnit.MILLISECONDS)).isNull();
        }

        @Override
        public void close() throws Exception {
            input.close();
            reader.join(Duration.ofSeconds(1));
        }
    }
}
