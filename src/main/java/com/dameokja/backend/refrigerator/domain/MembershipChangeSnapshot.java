package com.dameokja.backend.refrigerator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MembershipChangeSnapshot {
    private Long sequence;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MembershipChangeType type;
    private LocalDateTime occurredAt;
    @Column(length = 150)
    private String title;
    @Column(length = 500)
    private String body;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> recipientUserIds;

    public MembershipChangeSnapshot(long sequence, MembershipChangeType type, LocalDateTime occurredAt,
            String title, String body, List<Long> recipientUserIds) {
        if (sequence < 1) {
            throw new IllegalArgumentException("구성원 사건 순번은 양수여야 합니다.");
        }
        this.sequence = sequence;
        this.type = Objects.requireNonNull(type);
        this.occurredAt = Objects.requireNonNull(occurredAt).truncatedTo(ChronoUnit.MICROS);
        this.title = Objects.requireNonNull(title);
        this.body = Objects.requireNonNull(body);
        this.recipientUserIds = recipientUserIds.stream().distinct().sorted().toList();
    }
}
