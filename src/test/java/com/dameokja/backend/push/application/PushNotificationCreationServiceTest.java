package com.dameokja.backend.push.application;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationStatus;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.infrastructure.PushInboxTarget;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushNotificationCreationServiceTest {
    private static final LocalDateTime SEND_AT = LocalDateTime.of(2026, 9, 25, 8, 0);

    private final PushNotificationRepository pushNotificationRepository = mock(PushNotificationRepository.class);
    // 서버 Clock의 시간대와 상관없이 서울 기준으로 계산하는지 보려고 UTC Clock을 쓴다. (서울 2026-09-25 08:00)
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T23:00:00Z"), ZoneOffset.UTC);
    private final PushNotificationCreationService service = new PushNotificationCreationService(
            pushNotificationRepository, JsonMapper.builder().build(), clock);

    @Test
    void createsPendingJobsForSeoulTodayTargetsWithSeoulNoonDeadline() {
        User recipient = new User("회원", "default.png");
        UserDevice device = new UserDevice(recipient, "https://push.example.com/1", "p256dh",
                "encrypted".getBytes(), "enc-v1", "vapid-v1");
        PushInboxTarget target = new PushInboxTarget(notification(), device);
        when(pushNotificationRepository.findExpirationPushTargets(
                LocalDateTime.of(2026, 9, 24, 15, 0), LocalDateTime.of(2026, 9, 25, 15, 0)))
                .thenReturn(List.of(target));
        when(pushNotificationRepository.save(any(PushNotification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int created = service.createExpirationJobs();

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<PushNotification> saved = ArgumentCaptor.forClass(PushNotification.class);
        verify(pushNotificationRepository).save(saved.capture());
        PushNotification job = saved.getValue();
        assertThat(job.getStatus()).isEqualTo(PushNotificationStatus.PENDING);
        assertThat(job.getUser()).isSameAs(recipient);
        assertThat(job.getNextAttemptAt()).isEqualTo(SEND_AT);
        assertThat(job.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 9, 25, 12, 0));
        assertThat(job.getPayload()).isEqualTo("{\"notificationId\":7,\"refrigeratorId\":3,"
                + "\"type\":\"EXPIRING_SOON\",\"title\":\"임박 알림\",\"body\":\"우유가 곧 만료돼요\"}");
    }

    private Notification notification() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 3L);
        Notification notification = mock(Notification.class);
        when(notification.getId()).thenReturn(7L);
        when(notification.getRefrigerator()).thenReturn(refrigerator);
        when(notification.getType()).thenReturn(NotificationType.EXPIRING);
        when(notification.getTitle()).thenReturn("임박 알림");
        when(notification.getBody()).thenReturn("우유가 곧 만료돼요");
        return notification;
    }
}
