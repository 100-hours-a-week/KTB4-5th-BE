package com.dameokja.backend.push.application;

import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.push.domain.LeaseDuration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({PushLeaseService.class, PushLeaseServiceIntegrationTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PushLeaseServiceIntegrationTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 8, 0);
    private static final LeaseDuration LEASE_DURATION = new LeaseDuration(Duration.ofMinutes(1));
    private static final long OWNER_ID = 990500L;
    private static final List<Long> JOB_IDS = List.of(990501L, 990502L);

    @Autowired PushLeaseService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (?,'lease 수신자','p')", OWNER_ID);
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (?,'냉장고','2026-10')", OWNER_ID);
        jdbc.update("INSERT INTO user_devices(user_device_id,endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,"
                + "vapid_key_version,created_at,updated_at,user_id) VALUES (?,'https://push.example.com/lease-service',"
                + "'p256dh','secret','v1','v1',?,?,?)", OWNER_ID, NOW, NOW, OWNER_ID);
        for (Long id : JOB_IDS) {
            jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id) "
                    + "VALUES (?,'EXPIRED','알림','본문',?)", id, OWNER_ID);
            jdbc.update("""
                    INSERT INTO push_notifications(push_notification_id,dispatch_key,kind,user_device_id,
                        subscription_version,payload,status,attempt_count,next_attempt_at,expires_at,
                        created_at,updated_at,notification_id,user_id,refrigerator_id)
                    VALUES (?,?,'INBOX',?,1,'{}','PENDING',0,?,?,?,?,?,?,?)
                    """, id, "INBOX:" + id, OWNER_ID, NOW, NOW.withHour(12), NOW, NOW, id, OWNER_ID, OWNER_ID);
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM push_notifications WHERE push_notification_id IN (?,?)", JOB_IDS.getFirst(), JOB_IDS.getLast());
        jdbc.update("DELETE FROM notifications WHERE notification_id IN (?,?)", JOB_IDS.getFirst(), JOB_IDS.getLast());
        jdbc.update("DELETE FROM user_devices WHERE user_device_id=?", OWNER_ID);
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id=?", OWNER_ID);
        jdbc.update("DELETE FROM users WHERE user_id=?", OWNER_ID);
    }

    @Test
    void commitsTheLeaseBeforeReturningToTheCaller() {
        List<LeasedJob> jobs = service.lease(2, LEASE_DURATION);

        assertThat(jobs).extracting(LeasedJob::jobId).containsExactlyInAnyOrderElementsOf(JOB_IDS);
        for (LeasedJob job : jobs) {
            assertThat(status(job.jobId())).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT lease_token FROM push_notifications WHERE push_notification_id=?",
                    String.class, job.jobId())).isEqualTo(job.leaseToken());
            assertThat(jdbc.queryForObject("SELECT lease_until FROM push_notifications WHERE push_notification_id=?",
                    LocalDateTime.class, job.jobId())).isEqualTo(NOW.plusMinutes(1));
        }
        assertThat(service.lease(2, LEASE_DURATION)).isEmpty();
    }

    @Test
    void rollsBackLeaseChangesWhenTheOwningTransactionFails() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            assertThat(service.lease(2, LEASE_DURATION)).hasSize(2);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback");

        for (Long id : JOB_IDS) {
            assertThat(status(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT lease_token FROM push_notifications WHERE push_notification_id=?",
                    String.class, id)).isNull();
        }
    }

    @Test
    void leasesOtherJobsWhileAnotherTransactionHoldsTheFirstJob() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            transaction.executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT push_notification_id FROM push_notifications "
                        + "WHERE push_notification_id=? FOR UPDATE", Long.class, JOB_IDS.getFirst());
                List<LeasedJob> jobs = CompletableFuture.supplyAsync(() -> service.lease(2, LEASE_DURATION), executor)
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join();

                assertThat(jobs).extracting(LeasedJob::jobId).containsExactly(JOB_IDS.getLast());
            });
        }

        assertThat(status(JOB_IDS.getFirst())).isEqualTo("PENDING");
        assertThat(status(JOB_IDS.getLast())).isEqualTo("PROCESSING");
    }

    private String status(Long jobId) {
        return jdbc.queryForObject("SELECT status FROM push_notifications WHERE push_notification_id=?", String.class, jobId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock leaseClock() {
            return Clock.fixed(Instant.parse("2026-10-12T23:00:00Z"), ZoneOffset.UTC);
        }
    }
}
