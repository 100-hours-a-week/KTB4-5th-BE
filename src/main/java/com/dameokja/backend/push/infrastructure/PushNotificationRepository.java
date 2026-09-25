package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.PushNotification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushNotificationRepository extends JpaRepository<PushNotification, Long> {
}
