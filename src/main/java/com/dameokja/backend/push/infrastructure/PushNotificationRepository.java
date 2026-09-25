package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.notification.domain.NotificationPreferenceType;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushNotificationRepository extends JpaRepository<PushNotification, Long> {
    // 기간 안에 생성된 알림의 수신자 중 수신 설정을 켠 회원의 구독을 (알림, 구독) 쌍으로 찾는다.
    // 현재 구독 버전으로 이미 만든 작업은 제외하므로 다시 실행해도 작업이 중복되지 않는다.
    // select의 n, d는 Spring Data JPA가 반환 타입인 PushInboxTarget(notification, device) 생성자에 순서대로 넣는다.
    @Query("""
            select n, d
            from NotificationRecipient r
                join r.notification n
                join UserDevice d on d.user = r.user
                join NotificationPreference p on p.user = r.user
            where n.type in :notificationTypes
                and n.createdAt >= :from and n.createdAt < :to
                and d.status = :deviceStatus
                and p.type = :preferenceType and p.isEnabled = true
                and not exists (
                    select 1 from PushNotification job
                    where job.notification = n
                        and job.userDevice = d
                        and job.subscriptionVersion = d.subscriptionVersion)
            order by n.id, d.id
            """)
    List<PushInboxTarget> findInboxTargets(
            @Param("notificationTypes") Collection<NotificationType> notificationTypes,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("deviceStatus") UserDeviceStatus deviceStatus,
            @Param("preferenceType") NotificationPreferenceType preferenceType);
}
