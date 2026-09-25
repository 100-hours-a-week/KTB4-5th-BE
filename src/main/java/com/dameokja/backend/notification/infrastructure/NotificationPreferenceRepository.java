package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.NotificationPreference;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {
    List<NotificationPreference> findAllByUserId(Long userId);
}
