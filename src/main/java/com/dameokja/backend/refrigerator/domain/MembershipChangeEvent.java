package com.dameokja.backend.refrigerator.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

    /**
     * 업무 기록으로 같은 사건을 복원해도 변하지 않는 공통 UUID를 만든다.
     * @return 냉장고와 사건 순번에 대응하는 메시지 ID
     */
    public String messageId() {
        String identity = "fridgely:membership:v1:" + refrigeratorId + ":" + sequence;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
