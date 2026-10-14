package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.notification.application.NotificationScheduler;
import com.dameokja.backend.push.application.LeasedJob;
import com.dameokja.backend.push.application.PushDispatchWorker;
import com.dameokja.backend.push.application.PushNotificationUpdater;
import com.dameokja.backend.push.application.PushLeaseService;
import com.dameokja.backend.push.application.PushSendService;
import com.dameokja.backend.push.application.WorkerSlots;
import com.dameokja.backend.support.MySqlDatabaseTest;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.support.ServiceIntegrationTest.TestClock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.show-sql=false", "push.dispatch.enabled=true",
        "push.dispatch.poll-interval=50ms", "push.dispatch.concurrency=2"})
@ExtendWith(RedisTestSupport.class)
@Import({PushWorkerEndToEndTest.TestBeans.class, RedisTestSupport.Configuration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PushWorkerEndToEndTest extends MySqlDatabaseTest {
    private static final long OWNER = 998001L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 8, 0);
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationScheduler batch;
    @Autowired PushDispatchWorker worker;
    @Autowired PushNotificationUpdater updater;
    @Autowired WorkerSlots slots;
    @Autowired PushLeaseService leases;
    @Autowired PushNotificationRepository jobs;
    @Autowired PushSendService sender;
    @Autowired TestClock clock;
    @Autowired PushAuthEncryptor encryptor;
    @MockitoBean WebPushRequestFactory requestFactory;
    private PushWorkerHttpFixture http;

    @BeforeEach
    void setUp() throws Exception {
        clock.set("2026-09-24T23:00:00Z");
        http = new PushWorkerHttpFixture();
        // 실제 HTTP 전송과 응답 처리를 검사한다. 요청 암호화·외부 제공자 URL은 별도 테스트의 범위다.
        when(requestFactory.create(any(), any(), any(), any())).thenAnswer(invocation -> {
            HttpPost request = new HttpPost(http.url());
            request.setEntity(new StringEntity((String) invocation.getArgument(1)));
            return request;
        });
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (?,'워커E2E','test')", OWNER);
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (?,'워커E2E','2026-09')", OWNER);
        jdbc.update("INSERT INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES (?,?,1)", OWNER, OWNER);
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (?,'EXPIRATION',1)", OWNER);
    }

    @AfterEach
    void cleanUp() {
        http.unblock();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(slots.freeSlotCount()).isEqualTo(2));
        jdbc.update("DELETE FROM push_notifications WHERE user_id=?", OWNER);
        jdbc.update("DELETE FROM notification_recipients WHERE user_id=?", OWNER);
        jdbc.update("DELETE FROM notifications WHERE refrigerator_id=?", OWNER);
        jdbc.update("DELETE FROM user_devices WHERE user_id=?", OWNER);
        jdbc.update("DELETE FROM ingredients WHERE refrigerator_id=?", OWNER);
        jdbc.update("DELETE FROM notification_preferences WHERE user_id=?", OWNER);
        jdbc.update("DELETE FROM refrigerator_members WHERE user_id=?", OWNER);
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id=?", OWNER);
        jdbc.update("DELETE FROM users WHERE user_id=?", OWNER);
        http.close();
    }

    @Test
    void retriesTheSameRowOnlyWhenItsRetryTimeArrives() {
        devices(1);
        http.status(503);
        insertJob("PENDING", null, null);
        awaitStatus("RETRY", 1);
        List<Long> ids = jdbc.queryForList("SELECT push_notification_id FROM push_notifications WHERE user_id=?", Long.class, OWNER);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM push_notifications WHERE user_id=?",
                LocalDateTime.class, OWNER)).isEqualTo(NOW.plusMinutes(5));
        clock.set("2026-09-24T23:04:59Z");
        assertThat(worker.poll()).isEmpty();
        assertThat(http.requestCount()).isEqualTo(1);
        http.status(201);
        clock.set("2026-09-24T23:05:00Z");
        awaitStatus("ACCEPTED", 1);
        assertThat(http.requestCount()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT push_notification_id FROM push_notifications WHERE user_id=?", Long.class, OWNER)).isEqualTo(ids);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM push_notifications WHERE user_id=?", Integer.class, OWNER)).isEqualTo(2);
    }

    @Test
    void recoversAnExpiredLeaseAndRejectsThePreviousWorkersLateResult() throws Exception {
        devices(1);
        http.block(1);
        insertJob("PROCESSING", "old-server-token", NOW.plusSeconds(30));
        long id = jdbc.queryForObject("SELECT push_notification_id FROM push_notifications WHERE user_id=?", Long.class, OWNER);
        assertThat(worker.poll()).isEmpty();
        clock.set("2026-09-24T23:00:31Z");
        assertThat(http.awaitRequests()).isTrue();
        assertThat(updater.accept(new LeasedJob(id, "old-server-token"), NOW.plusSeconds(31))).isFalse();
        assertThat(count("PROCESSING")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM push_notifications WHERE user_id=?", String.class, OWNER))
                .isNotEqualTo("old-server-token");
        http.unblock();
        awaitStatus("ACCEPTED", 1);
        assertThat(http.requestCount()).isEqualTo(1);
    }

    @Test
    void cancelsAnUnavailableSubscriptionWithoutHttpAndReturnsItsSlot() {
        devices(1);
        jdbc.update("UPDATE user_devices SET status='DISABLED',subscription_version=2 WHERE user_id=?", OWNER);
        insertJob("PENDING", null, null);
        awaitStatus("CANCELLED", 1);
        assertThat(http.requestCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM push_notifications WHERE user_id=?", String.class, OWNER))
                .isEqualTo("SUBSCRIPTION_UNAVAILABLE");
    }

    @Test
    void stopsExpiredJobsWithoutHttp() {
        devices(1);
        clock.set("2026-09-25T03:00:00Z");
        insertJob("PENDING", null, null);
        awaitStatus("FAILED", 1);
        assertThat(http.requestCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM push_notifications WHERE user_id=?", String.class, OWNER))
                .isEqualTo("SEND_WINDOW_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM push_notifications WHERE user_id=?", Integer.class, OWNER)).isZero();
    }

    private void devices(int count) {
        for (int index = 0; index < count; index++) {
            jdbc.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,"
                    + "vapid_key_version,created_at,updated_at,user_id) VALUES (?,'test',?,'test-v1','test-v1',?,?,?)",
                    "https://fcm.googleapis.com/fcm/send/e2e-" + index, encryptor.encrypt("test-auth"), NOW, NOW, OWNER);
        }
    }

    private void insertJob(String status, String token, LocalDateTime leaseUntil) {
        jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id) VALUES (?,'EXPIRING','알림','본문',?)", OWNER, OWNER);
        jdbc.update("""
                INSERT INTO push_notifications(dispatch_key,kind,user_device_id,subscription_version,payload,status,
                    attempt_count,next_attempt_at,lease_token,lease_until,expires_at,created_at,updated_at,notification_id,user_id,refrigerator_id)
                SELECT 'E2E','INBOX',user_device_id,1,'{}',?,0,?,?,?,?,?,?,?,?,?
                FROM user_devices WHERE user_id=?
                """, status, NOW, token, leaseUntil, NOW.withHour(12), NOW, NOW, OWNER, OWNER, OWNER, OWNER);
    }

    private int count(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE user_id=? AND status=?", Integer.class, OWNER, status);
    }

    private void awaitStatus(String status, int expectedCount) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(count(status)).isEqualTo(expectedCount);
            assertThat(slots.freeSlotCount()).isEqualTo(2);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary TestClock workerTestClock() { return new TestClock(); }
        @Bean(destroyMethod = "close") @Primary
        CloseableHttpClient localHttpClient() { return HttpClients.custom().disableAutomaticRetries().build(); }
    }
}
