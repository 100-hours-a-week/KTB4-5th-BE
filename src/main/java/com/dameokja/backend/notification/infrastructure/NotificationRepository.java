package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE n FROM notifications n
            WHERE NOT EXISTS (
                SELECT 1 FROM notification_recipients r WHERE r.notification_id = n.notification_id
            ) AND NOT EXISTS (
                SELECT 1 FROM push_notifications p WHERE p.notification_id = n.notification_id
            )
            """, nativeQuery = true)
    int deleteUnreferencedNotifications();
}
