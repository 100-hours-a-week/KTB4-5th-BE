package com.dameokja.backend.notification.application;

import com.dameokja.backend.notification.domain.NotificationPreference;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;
import com.dameokja.backend.notification.infrastructure.NotificationPreferenceRepository;
import com.dameokja.backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationPreferenceService {
    private final NotificationPreferenceRepository notificationPreferenceRepository;

    public void createDefaults(User user) {
        for (NotificationPreferenceType type : NotificationPreferenceType.values()) {
            notificationPreferenceRepository.save(NotificationPreference.onSignup(user, type));
        }
    }
}
