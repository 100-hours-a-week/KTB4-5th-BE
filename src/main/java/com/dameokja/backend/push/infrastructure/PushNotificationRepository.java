package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.PushNotification;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushNotificationRepository extends JpaRepository<PushNotification, Long> {
    // 만료·임박 알림의 수신자 중 만료 알림 수신을 켠 활성 회원의 활성 구독을 찾는다.
    // 현재 구독 버전으로 이미 만든 작업은 제외하므로 같은 날 다시 실행해도 작업이 중복되지 않는다.
    @Query("select new com.dameokja.backend.push.infrastructure.PushInboxTarget(n, d) "
            + "from NotificationRecipient r join r.notification n join r.user u "
            + "join UserDevice d on d.user = u "
            + "join NotificationPreference p on p.user = u "
            + "where n.type in (com.dameokja.backend.notification.domain.NotificationType.EXPIRED, "
            + "com.dameokja.backend.notification.domain.NotificationType.EXPIRING) "
            + "and n.createdAt >= :from and n.createdAt < :to "
            + "and u.status = com.dameokja.backend.user.domain.UserStatus.ACTIVE "
            + "and d.status = com.dameokja.backend.push.domain.UserDeviceStatus.ACTIVE "
            + "and p.type = com.dameokja.backend.notification.domain.NotificationPreferenceType.EXPIRATION "
            + "and p.isEnabled = true "
            + "and not exists (select 1 from PushNotification job where job.notification = n "
            + "and job.userDevice = d and job.subscriptionVersion = d.subscriptionVersion) "
            + "order by n.id, d.id")
    List<PushInboxTarget> findExpirationPushTargets(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
