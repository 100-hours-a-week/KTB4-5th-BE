package com.dameokja.backend.refrigerator.domain;

/** 추방된 사용자와 기존 공유 냉장고를 식별하는 서버 내부 사건이다. */
public record MemberKicked(Long userId, Long refrigeratorId) {
    public MemberKicked {
        if (userId == null || userId <= 0 || refrigeratorId == null || refrigeratorId <= 0) {
            throw new IllegalArgumentException("추방 대상 사용자와 냉장고 식별자는 양수여야 합니다.");
        }
    }
}
