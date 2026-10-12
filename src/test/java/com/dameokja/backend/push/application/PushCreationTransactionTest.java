package com.dameokja.backend.push.application;

import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({PushNotificationCreationService.class, PushCreationTransactionTest.Configuration.class})
@TestPropertySource(properties = "push.expiration-batch.creation-chunk-size=2")
// 테스트의 일괄 롤백 대신 독립 커밋 후의 DB 상태를 검증한다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PushCreationTransactionTest extends MySqlJpaTest {
    @Autowired PushNotificationCreationService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedTargets() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (991001,'푸시분할','p')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (991001,'냉장고','2026-09')");
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (991001,'EXPIRATION',1)");
        device(991001);
        device(991002);
        notification(991001);
        notification(991002);
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_push_creation_chunk");
        jdbc.update("DELETE FROM push_notifications WHERE user_id=991001");
        jdbc.update("DELETE FROM notification_recipients WHERE user_id=991001");
        jdbc.update("DELETE FROM notifications WHERE refrigerator_id=991001");
        jdbc.update("DELETE FROM notification_preferences WHERE user_id=991001");
        jdbc.update("DELETE FROM user_devices WHERE user_id=991001");
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id=991001");
        jdbc.update("DELETE FROM users WHERE user_id=991001");
    }

    @Test
    void createsAllTargetsAcrossChunksAndRerunDoesNotDuplicateJobs() {
        assertThat(service.createExpirationJobs()).isEqualTo(4);
        assertThat(jobKeys()).containsExactly("991001:991001", "991001:991002", "991002:991001", "991002:991002");
        assertThat(service.createExpirationJobs()).isZero();
        assertThat(jobKeys()).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE user_id=991001 "
                + "AND next_attempt_at='2026-09-25 08:00:00' AND expires_at='2026-09-25 12:00:00'", Integer.class)).isEqualTo(4);
    }

    @Test
    void rollsBackFailingChunkKeepsEarlierCommitAndResumesWithoutDuplicates() {
        jdbc.execute("CREATE TRIGGER fail_push_creation_chunk BEFORE INSERT ON push_notifications "
                + "FOR EACH ROW BEGIN IF NEW.notification_id=991002 AND NEW.user_device_id=991002 "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='push chunk failure'; END IF; END");

        assertThatThrownBy(service::createExpirationJobs).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("push chunk failure");
        assertThat(jobKeys()).containsExactly("991001:991001", "991001:991002");

        jdbc.execute("DROP TRIGGER fail_push_creation_chunk");
        assertThat(service.createExpirationJobs()).isEqualTo(2);
        assertThat(jobKeys()).containsExactly("991001:991001", "991001:991002", "991002:991001", "991002:991002");
    }

    private List<String> jobKeys() {
        return jdbc.queryForList("SELECT CONCAT(notification_id,':',user_device_id) FROM push_notifications "
                + "WHERE user_id=991001 ORDER BY notification_id,user_device_id", String.class);
    }

    private void device(long id) {
        jdbc.update("INSERT INTO user_devices(user_device_id,endpoint,p256dh_key,auth_secret_encrypted,"
                + "encryption_key_version,vapid_key_version,status,created_at,updated_at,user_id) "
                + "VALUES (?,?,'p256dh','secret','v1','v1','ACTIVE',NOW(6),NOW(6),991001)", id, "https://push.example.com/" + id);
    }

    private void notification(long id) {
        jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id,created_at,updated_at) "
                + "VALUES (?,'EXPIRED','알림','본문',991001,'2026-09-25 08:00:00','2026-09-25 08:00:00')", id);
        jdbc.update("INSERT INTO notification_recipients(notification_id,user_id) VALUES (?,991001)", id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        Clock pushCreationClock() {
            return Clock.fixed(Instant.parse("2026-09-24T23:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper pushCreationMapper() {
            return JsonMapper.builder().build();
        }
    }
}
