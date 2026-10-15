package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisPushCreationTest extends MySqlJpaTest {
    @Autowired PushNotificationRepository repository;
    @Autowired JdbcTemplate jdbc;
    private final NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), BusinessTime.ZONE);
    private final OffsetDateTime deadline = clock.instant().plusSeconds(3600).atZone(BusinessTime.ZONE).toOffsetDateTime();
    private PushAnalysisCreationService service;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (980001,'분석회원','p')");
        jdbc.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,vapid_key_version,created_at,updated_at,user_id) "
                + "VALUES ('https://push.example.com/analysis','key','secret','v1','v1',NOW(6),NOW(6),980001)");
        when(preferences.isAnalysisEnabled(980001L)).thenReturn(true);
        service = new PushAnalysisCreationService(repository, preferences, JsonMapper.builder().build(), clock);
    }

    @Test
    void persistsPushOnlyOnceEvenWhenSubscriptionChangesBeforeCallbackRetry() {
        service.create("group-one", 980001L, deadline);
        flushAndClear();
        jdbc.update("UPDATE user_devices SET subscription_version=2 WHERE user_id=980001");
        service.create("group-one", 980001L, deadline);
        flushAndClear();
        assertThat(repository.findAll()).singleElement().satisfies(job -> {
            assertThat(job.getNotification()).isNull();
            assertThat(job.getRefrigerator()).isNull();
            assertThat(job.getSubscriptionVersion()).isEqualTo(1L);
            assertThat(job.getExpiresAt()).isEqualTo(deadline.toLocalDateTime());
            assertThat(JsonMapper.builder().build().readTree(job.getPayload())).isEqualTo(JsonMapper.builder().build().readTree("""
                    {"type":"ANALYSIS","analysisId":"group-one","expiresAt":"2026-10-14T13:00:00+09:00",
                     "title":"이미지 분석 종료","body":"이미지 분석이 끝났습니다. 결과를 확인해 주세요."}
                    """));
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipients", Long.class)).isZero();
    }

    @Test
    void skipsWithdrawnUserAndExpiredResults() {
        jdbc.update("UPDATE users SET status='WITHDRAWN',deleted_at=NOW(6) WHERE user_id=980001");
        service.create("group-withdrawn", 980001L, deadline);
        service.create("group-expired", 980001L, deadline.minusHours(1));
        assertThat(repository.findAll()).isEmpty();
    }
}
