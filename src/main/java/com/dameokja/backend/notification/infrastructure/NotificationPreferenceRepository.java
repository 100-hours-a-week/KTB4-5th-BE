package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.NotificationPreference;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {
    List<NotificationPreference> findAllByUserId(Long userId);
    Optional<NotificationPreference> findByUserIdAndType(Long userId, NotificationPreferenceType type);
    boolean existsByUserIdAndTypeAndIsEnabledTrue(Long userId, NotificationPreferenceType type);
}
