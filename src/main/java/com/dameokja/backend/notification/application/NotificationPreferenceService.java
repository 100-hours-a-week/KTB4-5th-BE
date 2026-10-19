package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.domain.NotificationExceptionCode;
import com.dameokja.backend.notification.domain.NotificationPreference;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;
import com.dameokja.backend.notification.infrastructure.NotificationPreferenceRepository;
import com.dameokja.backend.user.domain.User;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    public void create(User user, boolean enabled) {
        for (NotificationPreferenceType type : NotificationPreferenceType.values()) {
            notificationPreferenceRepository.save(NotificationPreference.onSignup(user, type, enabled));
        }
    }

    public List<NotificationPreferenceView> change(Long userId, Map<NotificationPreferenceType, Boolean> changes) {
        changes.forEach((type, enabled) -> notificationPreferenceRepository.findByUserIdAndType(userId, type)
                .orElseThrow(() -> new CustomException(NotificationExceptionCode.PREFERENCE_NOT_FOUND))
                .changeEnabled(enabled));
        return getPreferences(userId);
    }

    @Transactional(readOnly = true)
    public boolean isAnalysisEnabled(Long userId) {
        return notificationPreferenceRepository.existsByUserIdAndTypeAndIsEnabledTrue(userId, NotificationPreferenceType.ANALYSIS);
    }

    @Transactional(readOnly = true)
    public List<NotificationPreferenceView> getPreferences(Long userId) {
        return notificationPreferenceRepository.findAllByUserId(userId).stream()
                .sorted(Comparator.comparing(NotificationPreference::getType))
                .map(NotificationPreferenceView::from)
                .toList();
    }
}
