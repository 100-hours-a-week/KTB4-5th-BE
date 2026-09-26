package com.dameokja.backend.notification.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {
    private static final int MAX_TITLE_LENGTH = 150;
    private static final int MAX_BODY_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(nullable = false, length = MAX_BODY_LENGTH)
    private String body;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refrigerator_id", nullable = false, updatable = false)
    private Refrigerator refrigerator;

    public Notification(NotificationType type, String title, String body, Refrigerator refrigerator) {
        this.type = Objects.requireNonNull(type, "알림 유형은 필수입니다.");
        this.title = validateText(title, MAX_TITLE_LENGTH, "알림 제목");
        this.body = validateText(body, MAX_BODY_LENGTH, "알림 본문");
        this.refrigerator = Objects.requireNonNull(refrigerator, "알림의 냉장고는 필수입니다.");
    }

    private static String validateText(String text, int maxLength, String fieldName) {
        Objects.requireNonNull(text, fieldName + "은 필수입니다.");
        if (text.codePointCount(0, text.length()) > maxLength) {
            throw new IllegalArgumentException(fieldName + "은 " + maxLength + "자를 초과할 수 없습니다.");
        }
        return text;
    }
}
