package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisPushDispatchQueryTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 16, 0);
    @Autowired PushNotificationRepository repository;
    @Autowired JdbcTemplate jdbc;
    private Long jobId;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (970001,'수신자','p'),(970002,'타인','p')");
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (970001,'ANALYSIS',1),(970001,'EXPIRATION',0)");
        jdbc.update("INSERT INTO user_devices(user_device_id,user_id,endpoint,p256dh_key,auth_secret_encrypted,"
                + "encryption_key_version,vapid_key_version,created_at,updated_at) VALUES (970001,970001,'https://push.example.com/970001','key','secret','v1','v1',NOW(6),NOW(6))");
        UserDevice device = entityManager.find(UserDevice.class, 970001L);
        jobId = repository.save(PushNotification.analysis("analysis-1", device.getUser(), device, "{}", NOW, NOW.plusHours(1))).getId();
        flushAndClear();
    }

    @Test
    void requiresCurrentActiveOwnerAndAnalysisPreferenceAndOriginalSubscriptionVersion() {
        assertThat(canReceive()).isTrue();
        jdbc.update("UPDATE notification_preferences SET is_enabled=0 WHERE user_id=970001 AND type='ANALYSIS'");
        assertThat(canReceive()).isFalse();
        jdbc.update("UPDATE notification_preferences SET is_enabled=1 WHERE user_id=970001 AND type='ANALYSIS'");
        jdbc.update("UPDATE users SET status='WITHDRAWN',deleted_at=NOW(6) WHERE user_id=970001");
        assertThat(canReceive()).isFalse();
        jdbc.update("UPDATE users SET status='ACTIVE',deleted_at=NULL WHERE user_id=970001");
        jdbc.update("UPDATE user_devices SET user_id=970002 WHERE user_device_id=970001");
        assertThat(canReceive()).isFalse();
        jdbc.update("UPDATE user_devices SET user_id=970001,subscription_version=2 WHERE user_device_id=970001");
        assertThat(canReceive()).isFalse();
        jdbc.update("UPDATE user_devices SET subscription_version=1,status='INVALID' WHERE user_device_id=970001");
        assertThat(canReceive()).isFalse();
        jdbc.update("UPDATE user_devices SET status='ACTIVE' WHERE user_device_id=970001");
        assertThat(canReceive()).isTrue();
    }

    @Test
    void leasesAnalysisJobAndLoadsItWithoutRefrigerator() {
        assertThat(repository.findLeaseableJobIdsWithSkipLock(NOW, 20)).containsExactly(jobId);
        repository.leasePushJobs(List.of(jobId), "analysis-lease", NOW, NOW.plusMinutes(1));
        flushAndClear();
        assertThat(repository.findLeasedPushJob(jobId, "analysis-lease", NOW)).hasValueSatisfying(job -> {
            assertThat(job.getRefrigerator()).isNull();
            assertThat(job.getNotification()).isNull();
        });
    }

    private boolean canReceive() {
        return repository.canReceiveAnalysisPush(jobId);
    }
}
