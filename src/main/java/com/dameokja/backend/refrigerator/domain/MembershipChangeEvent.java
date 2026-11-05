package com.dameokja.backend.refrigerator.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public record MembershipChangeEvent(
        Long refrigeratorId, Long membershipId, Long subjectUserId, Long sequence,
        MembershipChangeType type, LocalDateTime occurredAt, String title, String body,
        List<Long> recipientUserIds) {

    public MembershipChangeEvent {
        if (refrigeratorId == null || membershipId == null || subjectUserId == null || sequence == null
                || refrigeratorId < 1 || membershipId < 1 || subjectUserId < 1 || sequence < 1) {
            throw new IllegalArgumentException("구성원 사건 식별자와 순번이 올바르지 않습니다.");
        }
        Objects.requireNonNull(type);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(title);
        Objects.requireNonNull(body);
        recipientUserIds = List.copyOf(recipientUserIds);
    }

    public static MembershipChangeEvent from(RefrigeratorMember member, MembershipChangeSnapshot snapshot) {
        return new MembershipChangeEvent(member.getRefrigerator().getId(), member.getId(), member.getUser().getId(),
                snapshot.getSequence(), snapshot.getType(), snapshot.getOccurredAt(), snapshot.getTitle(),
                snapshot.getBody(), snapshot.getRecipientUserIds());
    }

}
