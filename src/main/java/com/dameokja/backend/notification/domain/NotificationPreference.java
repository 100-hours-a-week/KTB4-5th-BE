package com.dameokja.backend.notification.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 수신을 끈 사실도 유지해야 하므로 행을 삭제하지 않고 is_enabled만 바꾼다.
@Getter
@Entity
@Table(name = "notification_preferences")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preference_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private NotificationPreferenceType type;

    @Column(name = "is_enabled", nullable = false, columnDefinition = "tinyint(1)")
    private Boolean isEnabled;

    public static NotificationPreference onSignup(User user, NotificationPreferenceType type) {
        NotificationPreference preference = new NotificationPreference();
        preference.user = user;
        preference.type = type;
        preference.isEnabled = type.isEnabledOnSignup();
        return preference;
    }
}
