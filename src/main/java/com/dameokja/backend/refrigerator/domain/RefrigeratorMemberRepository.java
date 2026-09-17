package com.dameokja.backend.refrigerator.domain;

import java.util.Optional;

public interface RefrigeratorMemberRepository {
    RefrigeratorMember save(RefrigeratorMember member);
    Optional<RefrigeratorMember> findById(Long id);
    Optional<RefrigeratorMember> findActiveByUserId(Long userId);
    long deleteAllByRefrigeratorId(Long refrigeratorId);
    java.util.List<RefrigeratorMember> findOwnedByUserId(Long userId);
}
