package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.NotificationRecipient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, Long> {
    @Query("select r from NotificationRecipient r join fetch r.notification n "
            + "where r.user.id = :userId and n.refrigerator.id = :refrigeratorId "
            + "and (:readFilter = 'ALL' or (:readFilter = 'READ' and r.readAt is not null) "
            + "or (:readFilter = 'UNREAD' and r.readAt is null)) "
            + "and (:cursorTime is null or n.createdAt < :cursorTime "
            + "or (n.createdAt = :cursorTime and n.id < :cursorId)) "
            + "order by n.createdAt desc, n.id desc")
    List<NotificationRecipient> findPage(@Param("userId") Long userId,
            @Param("refrigeratorId") Long refrigeratorId, @Param("readFilter") String readFilter,
            @Param("cursorTime") LocalDateTime cursorTime, @Param("cursorId") Long cursorId,
            Pageable pageable);

    Optional<NotificationRecipient> findByNotificationIdAndUserId(Long notificationId, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationRecipient r set r.readAt = :readAt "
            + "where r.user.id = :userId and r.readAt is null "
            + "and r.notification.refrigerator.id = :refrigeratorId")
    int markAllRead(@Param("userId") Long userId,
            @Param("refrigeratorId") Long refrigeratorId, @Param("readAt") LocalDateTime readAt);

    @Query("select count(r) from NotificationRecipient r "
            + "where r.user.id = :userId and r.readAt is null "
            + "and r.notification.refrigerator.id = :refrigeratorId")
    long countUnread(@Param("userId") Long userId, @Param("refrigeratorId") Long refrigeratorId);
}
